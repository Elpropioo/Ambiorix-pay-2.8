package com.ambiorix.pay.notification

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ambiorix.pay.data.KnownApps
import com.ambiorix.pay.data.Payment
import com.ambiorix.pay.data.PaymentRepository
import com.ambiorix.pay.data.SettingsRepository
import com.ambiorix.pay.sms.PaymentDeduplicator

/**
 * Servicio de escucha de notificaciones de Android.
 *
 * Principios de seguridad y privacidad:
 * - NO lee extras ni textos si la app no está explícitamente autorizada en la configuración.
 * - NO imprime montos, nombres ni contenidos de notificaciones en el log del sistema (Logcat).
 * - Cero conexiones a internet.
 * - Soporta reconexión automática mediante requestRebind().
 */
class AmbiorixNotificationListener : NotificationListenerService() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var paymentRepository: PaymentRepository
    private val filter = PaymentNotificationFilter()

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        paymentRepository = PaymentRepository.getInstance(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationPaymentEvents.setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationPaymentEvents.setListenerConnected(false)
        // Intentar reconectar el servicio inmediatamente
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, AmbiorixNotificationListener::class.java))
            } catch (e: Exception) {
                // Falla silenciosa sin exponer datos sensibles
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return

        val settings = settingsRepository.getSettings()

        // 1. REGLA DE SERVICIO ENCENDIDO / APAGADO:
        // Si el usuario apagó el servicio Ambiorix Pay, ignorar completamente sin procesar.
        if (!settings.isServiceEnabled) {
            return
        }

        // 2. REGLA DE HORARIO COMERCIAL:
        // Fuera de horario comercial no se anuncian pagos ni se generan confirmaciones.
        val isWithinHours = settings.businessHours.isWithinBusinessHours()
        if (!isWithinHours) {
            return
        }

        // 3. REGLA CRÍTICA DE PRIVACIDAD:
        // Si smsPaymentEnabled está activo, autorizar automáticamente los paquetes de Mensajes SMS
        // únicamente para detectar notificaciones bancarias de Nequi (890806 / Bre-B).
        val baseAuthorized = settings.enabledPackages + settings.customPackages
        val allAuthorized = if (settings.smsPaymentEnabled) {
            baseAuthorized + KnownApps.SMS_PACKAGES
        } else {
            baseAuthorized - KnownApps.SMS_PACKAGES
        }
        val isAuthorized = allAuthorized.any { it.equals(packageName, ignoreCase = true) }
        if (!isAuthorized) {
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extraer título y texto sin loguear sus contenidos
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val fullBody = if (!bigText.isNullOrBlank()) bigText else text

        val postTime = sbn.postTime
        val exactReceptionMillis = if (postTime > 0) postTime else System.currentTimeMillis()

        // Si proviene de la app de Mensajes SMS del teléfono (Google Messages, Samsung Messages, etc.),
        // verificar que sea específicamente un SMS de pago de Nequi / Bre-B y no un chat personal.
        if (KnownApps.isSmsPackage(packageName)) {
            if (!settings.smsPaymentEnabled) return

            val smsResult = com.ambiorix.pay.sms.NequiSmsParser.parse(title, fullBody)
            if (smsResult is com.ambiorix.pay.sms.SmsPaymentParseResult.Success) {
                val parsed = smsResult.payment
                val fingerprint = PaymentDeduplicator.computeSmsFingerprint(
                    amount = parsed.amount,
                    senderName = parsed.senderName,
                    receiverBusiness = parsed.receiverBusiness,
                    dateTimeText = parsed.transactionDateTimeText,
                    channel = parsed.channel,
                    rawBody = fullBody.orEmpty()
                )
                if (PaymentDeduplicator.isDuplicateSms(fingerprint, exactReceptionMillis)) return
                if (PaymentDeduplicator.isDuplicateCrossSourceForPush(parsed.amount, exactReceptionMillis)) return
                PaymentDeduplicator.markSmsProcessed(fingerprint, parsed.amount, parsed.senderName, exactReceptionMillis)
                PaymentDeduplicator.recordPushPayment(parsed.amount, exactReceptionMillis)

                val payment = Payment(
                    amount = parsed.amount,
                    appName = "Nequi SMS",
                    packageName = "com.nequi.MobileApp",
                    timestampMillis = exactReceptionMillis,
                    senderName = parsed.senderName,
                    channel = parsed.channel ?: "Nequi",
                    source = com.ambiorix.pay.data.PaymentSource.SMS,
                    hasExactSeconds = true
                )
                paymentRepository.recordPayment(payment)
                NotificationPaymentEvents.emitRealPayment(payment, applicationContext)
                return
            }

            // Si no coincidió con el patrón estricto, solo permitir evaluación si el remitente o cuerpo es de Nequi / Bre-B
            if (!isNequiSmsNotification(title, fullBody)) {
                return
            }
        }

        // Evaluar con el filtro determinista
        val result = filter.evaluate(
            packageName = packageName,
            title = title,
            text = fullBody,
            notificationPostTimeMillis = postTime,
            authorizedPackages = allAuthorized,
            isServiceEnabled = settings.isServiceEnabled,
            isWithinBusinessHours = isWithinHours
        )

        when (result) {
            is FilterResult.Accepted -> {
                // Verificar deduplicación cruzada con SMS por si la misma transacción ya fue anunciada
                if (PaymentDeduplicator.isDuplicateCrossSourceForPush(result.amount, exactReceptionMillis)) {
                    return
                }
                PaymentDeduplicator.recordPushPayment(result.amount, exactReceptionMillis)

                val isSmsApp = KnownApps.isSmsPackage(packageName)
                val appDisplayName = if (isSmsApp) "Nequi SMS" else KnownApps.getDisplayName(packageName)
                val payment = Payment(
                    amount = result.amount,
                    appName = appDisplayName,
                    packageName = if (isSmsApp) "com.nequi.MobileApp" else packageName,
                    timestampMillis = exactReceptionMillis,
                    senderName = result.senderName,
                    source = if (isSmsApp) com.ambiorix.pay.data.PaymentSource.SMS else com.ambiorix.pay.data.PaymentSource.NOTIFICATION,
                    hasExactSeconds = true
                )

                // Guardar en el repositorio local de historial
                paymentRepository.recordPayment(payment)

                // Emitir por el canal en memoria hacia la interfaz gráfica
                NotificationPaymentEvents.emitRealPayment(payment, applicationContext)
            }
            is FilterResult.Rejected -> {
                // Descartada silenciosamente sin escribir montos en logs
            }
        }
    }

    private fun isNequiSmsNotification(title: String?, body: String?): Boolean {
        val cleanTitle = title?.trim().orEmpty()
        val lowerTitle = cleanTitle.lowercase()
        val lowerBody = body?.lowercase().orEmpty()
        val knownShortCodes = setOf("890806", "85954", "85432", "87700")
        return cleanTitle in knownShortCodes ||
                lowerTitle.contains("890806") ||
                lowerTitle.contains("85954") ||
                lowerTitle.contains("nequi") ||
                lowerBody.contains("nequi") ||
                lowerBody.contains("bre-b")
    }
}
