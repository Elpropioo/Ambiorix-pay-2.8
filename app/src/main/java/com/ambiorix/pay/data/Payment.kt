package com.ambiorix.pay.data

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Origen de la transacción detectada.
 */
enum class PaymentSource {
    NOTIFICATION, // Notificación Push de Android
    SMS,          // Mensaje de texto SMS (ej. Nequi 890806)
    SIMULATED;    // Prueba manual desde la app

    companion object {
        val PUSH_NOTIFICATION: PaymentSource = NOTIFICATION
    }
}

/**
 * Estado interno de la app.
 * La app NUNCA afirma que el banco confirmó el pago.
 * El estado interno es PAYMENT_DETECTED y la interfaz dice "PAGO RECIBIDO".
 */
enum class PaymentStatus {
    IDLE,
    PAYMENT_DETECTED
}

/**
 * Representa una transacción detectada a través del NotificationListenerService o del receptor de SMS.
 * Conserva precisión de milisegundos/segundos y formatea fechas consistentemente en zona horaria de Colombia.
 */
data class Payment(
    val id: String = UUID.randomUUID().toString(),
    val amount: Long,
    val appName: String,
    val packageName: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val senderName: String? = null,              // Nombre completo del remitente del pago (ej. SANTIAGO ALEJANDRO MORGAN AVILA)
    val channel: String? = null,                 // Canal o red de transferencia (ej. Bre-B, Nequi)
    val receiverBusiness: String? = null,        // Nombre del negocio receptor extraído (ej. TECNICABLES)
    val transactionDateTimeText: String? = null, // Fecha y hora textual de la notificación (ej. 19/10/2026 a las 07:11 p.m.)
    val source: PaymentSource = PaymentSource.NOTIFICATION,
    val hasExactSeconds: Boolean = true          // Indica si el registro conserva segundos reales de recepción
) {
    val formattedAmount: String
        get() = formatCop(amount)

    /**
     * Hora en formato de 24 horas con segundos reales (ej: "19:11:42") en zona horaria de Colombia.
     * Si un registro histórico antiguo no conservaba segundos exactos, muestra solo HH:mm honestamente.
     */
    val formattedExactTime: String
        get() {
            val pattern = if (hasExactSeconds) "HH:mm:ss" else "HH:mm"
            val sdf = SimpleDateFormat(pattern, Locale("es", "CO")).apply {
                timeZone = COLOMBIA_TIME_ZONE
            }
            return sdf.format(Date(timestampMillis))
        }

    /**
     * Hora legible con segundos para la pantalla de confirmación y listados.
     */
    val formattedTime: String
        get() {
            val pattern = if (hasExactSeconds) "HH:mm:ss" else "HH:mm"
            val sdf = SimpleDateFormat(pattern, Locale("es", "CO")).apply {
                timeZone = COLOMBIA_TIME_ZONE
            }
            return sdf.format(Date(timestampMillis))
        }

    /**
     * Fecha en formato dd/MM/yyyy en zona horaria de Colombia (evita cambios de día por UTC).
     */
    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale("es", "CO")).apply {
                timeZone = COLOMBIA_TIME_ZONE
            }
            return sdf.format(Date(timestampMillis))
        }

    /**
     * Identificador interno corto y legible para auditoría en el historial.
     */
    val displayInternalId: String
        get() {
            val clean = id.trim()
            if (clean.isEmpty()) return "SIN-ID"
            return if (clean.length > 8) {
                clean.take(8).uppercase(Locale.ROOT)
            } else {
                clean.uppercase(Locale.ROOT)
            }
        }

    companion object {
        val COLOMBIA_TIME_ZONE: TimeZone = TimeZone.getTimeZone("America/Bogota")

        fun formatCop(amount: Long): String {
            val format = NumberFormat.getCurrencyInstance(Locale("es", "CO"))
            format.maximumFractionDigits = 0
            return format.format(amount)
        }
    }
}
