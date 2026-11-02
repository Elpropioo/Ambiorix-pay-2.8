package com.ambiorix.pay.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ambiorix.pay.data.Payment
import com.ambiorix.pay.data.PaymentRepository
import com.ambiorix.pay.data.PaymentSource
import com.ambiorix.pay.data.SettingsRepository
import com.ambiorix.pay.notification.ColombianPaymentKeywords
import com.ambiorix.pay.notification.NotificationPaymentEvents
import com.ambiorix.pay.notification.PaymentNotificationParser
import com.ambiorix.pay.pos.NotificationState
import com.ambiorix.pay.pos.PosRepository

/**
 * Receptor de SMS de Android para:
 * 1. Coordinación P2P entre locales (Heladería <-> Pizzería) mediante protocolo AMBIPAY.
 * 2. Notificaciones bancarias de Nequi y Bre-B (confirmación inmediata en Kiosco y conciliación POS).
 */
class AmbiorixSmsReceiver : BroadcastReceiver() {

    private val fallbackParser = PaymentNotificationParser()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val appContext = context.applicationContext
        val posRepository = PosRepository.getInstance(appContext)

        if (intent.action == PosRepository.ACTION_SMS_SENT) {
            val msgId = intent.getStringExtra(PosRepository.EXTRA_MSG_ID).orEmpty()
            val entityId = intent.getStringExtra(PosRepository.EXTRA_ENTITY_ID).orEmpty()
            val isSuccess = (resultCode == android.app.Activity.RESULT_OK)
            posRepository.onSmsSendResult(
                msgId = msgId,
                relatedEntityId = entityId,
                isSuccess = isSuccess,
                errorCode = resultCode
            )
            return
        }

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages.firstOrNull()?.originatingAddress?.trim()
        val fullBody = messages.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
        if (fullBody.isEmpty()) return

        // Si es un mensaje de coordinación entre tiendas AMBIPAY|..., procesarlo directamente con el número real del remitente
        if (com.ambiorix.pay.pos.AmbiorixSmsProtocol.isProtocolMessage(fullBody)) {
            val replyPhone = sender?.takeIf { it.isNotBlank() } ?: posRepository.getPeerPhone()
            posRepository.processIncomingCoordinationSms(fullBody, replyPhone)
            return
        }

        val settings = SettingsRepository.getInstance(appContext).getSettings()
        if (!settings.isServiceEnabled || !settings.smsPaymentEnabled) return
        if (!settings.businessHours.isWithinBusinessHours()) return

        val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        // Procesar notificación bancaria Nequi en el repositorio POS
        val bankNotif = posRepository.processNequiBankSms(
            rawBody = fullBody,
            originatingAddress = sender,
            timestamp = ts
        )

        var resolvedAmount: Long? = null
        var resolvedSenderName: String? = null
        var resolvedChannel: String = "Nequi"

        if (bankNotif != null && bankNotif.amount > 0L) {
            if (bankNotif.state == NotificationState.DUPLICATE_EVENT) {
                return
            }
            // Si el modo multi-tienda P2P está activo y la reserva pertenece exclusivamente al otro local, no anunciar aquí
            if (settings.peerStoreEnabled && bankNotif.state == NotificationState.MATCH_CANDIDATE) {
                return
            }
            resolvedAmount = bankNotif.amount
            resolvedSenderName = bankNotif.senderName
            resolvedChannel = bankNotif.channel ?: "Nequi"
        } else if (isLikelyNequiSms(sender, fullBody)) {
            // Respaldo para variantes de texto SMS de Nequi / Bre-B con estructura libre
            val normalized = ColombianPaymentKeywords.normalizeText(fullBody)
            val hasExclusion = ColombianPaymentKeywords.EXCLUSION_KEYWORDS.any { normalized.contains(it) }
            val hasIncoming = ColombianPaymentKeywords.INCOMING_PAYMENT_KEYWORDS.any { normalized.contains(it) }
            if (!hasExclusion && hasIncoming) {
                val extractedAmount = fallbackParser.extractAmount(sender ?: "Nequi", fullBody)
                if (extractedAmount != null && extractedAmount > 0L) {
                    resolvedAmount = extractedAmount
                    resolvedSenderName = fallbackParser.extractSenderName(sender ?: "Nequi", fullBody)
                    resolvedChannel = if (normalized.contains("bre-b") || normalized.contains("breb")) "Bre-B" else "Nequi"
                }
            }
        }

        val finalAmount = resolvedAmount ?: return
        val fingerprint = PaymentDeduplicator.computeSmsFingerprint(
            amount = finalAmount,
            senderName = resolvedSenderName.orEmpty(),
            receiverBusiness = null,
            dateTimeText = null,
            channel = resolvedChannel,
            rawBody = fullBody
        )

        if (PaymentDeduplicator.isDuplicateSms(fingerprint, ts)) return
        if (PaymentDeduplicator.isDuplicateCrossSourceForSms(finalAmount, ts)) return
        PaymentDeduplicator.markSmsProcessed(fingerprint, finalAmount, resolvedSenderName, ts)

        val payment = Payment(
            amount = finalAmount,
            appName = "Nequi SMS",
            packageName = "com.nequi.MobileApp",
            timestampMillis = ts,
            senderName = resolvedSenderName,
            channel = resolvedChannel,
            source = PaymentSource.SMS,
            hasExactSeconds = true
        )
        PaymentRepository.getInstance(appContext).recordPayment(payment)
        NotificationPaymentEvents.emitRealPayment(payment, appContext)
    }

    private fun isLikelyNequiSms(sender: String?, body: String): Boolean {
        val cleanSender = sender?.trim().orEmpty()
        val lowerBody = body.lowercase()
        val knownShortCodes = setOf("890806", "85954", "85432", "87700")
        return cleanSender in knownShortCodes ||
                cleanSender.contains("nequi", ignoreCase = true) ||
                lowerBody.contains("nequi") ||
                lowerBody.contains("bre-b")
    }
}
