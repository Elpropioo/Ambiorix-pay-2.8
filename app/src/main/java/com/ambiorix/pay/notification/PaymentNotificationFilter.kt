package com.ambiorix.pay.notification

/**
 * Razones de rechazo para auditoría y pruebas unitarias.
 */
enum class RejectionReason {
    SERVICE_DISABLED,
    OUTSIDE_BUSINESS_HOURS,
    UNAUTHORIZED_APP,
    EXCLUSION_KEYWORD_FOUND,
    NO_INCOMING_KEYWORD_FOUND,
    INVALID_OR_MISSING_AMOUNT,
    DUPLICATE_WITHIN_WINDOW,
    NOTIFICATION_TOO_OLD
}

sealed class FilterResult {
    data class Accepted(
        val amount: Long,
        val senderName: String? = null
    ) : FilterResult()

    data class Rejected(val reason: RejectionReason) : FilterResult()
}

/**
 * Filtro de notificaciones de pago con reloj inyectable para pruebas unitarias sin Android.
 * Aplica filtros en estricto orden secuencial:
 * (0) Servicio activo y dentro de horario comercial
 * (1) App autorizada
 * (2) Palabras de pago recibido y palabras de exclusión
 * (3) Monto válido
 * (4) No duplicada en una ventana de 2 minutos (120 s)
 * (5) Notificación reciente (máximo 3 minutos de antigüedad)
 */
class PaymentNotificationFilter(
    private val parser: PaymentNotificationParser = PaymentNotificationParser(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private data class RecentDetection(
        val packageName: String,
        val amount: Long,
        val timestamp: Long
    )

    private val recentDetections = mutableListOf<RecentDetection>()

    companion object {
        const val DUPLICATE_WINDOW_MILLIS = 2 * 60 * 1000L // 2 minutos
        const val MAX_NOTIFICATION_AGE_MILLIS = 3 * 60 * 1000L // 3 minutos
    }

    @Synchronized
    fun evaluate(
        packageName: String,
        title: String?,
        text: String?,
        notificationPostTimeMillis: Long,
        authorizedPackages: Set<String>,
        isServiceEnabled: Boolean = true,
        isWithinBusinessHours: Boolean = true
    ): FilterResult {
        // 0. Servicio activo
        if (!isServiceEnabled) {
            return FilterResult.Rejected(RejectionReason.SERVICE_DISABLED)
        }

        // 0.1 Horario comercial
        if (!isWithinBusinessHours) {
            return FilterResult.Rejected(RejectionReason.OUTSIDE_BUSINESS_HOURS)
        }

        val now = clock()

        // 1. App autorizada
        val isAuthorized = authorizedPackages.any { it.equals(packageName, ignoreCase = true) }
        if (!isAuthorized) {
            return FilterResult.Rejected(RejectionReason.UNAUTHORIZED_APP)
        }

        // Unificar y normalizar texto
        val combinedText = buildString {
            if (!title.isNullOrBlank()) append(title).append(" ")
            if (!text.isNullOrBlank()) append(text)
        }
        val normalized = ColombianPaymentKeywords.normalizeText(combinedText)

        // 2. Palabras clave:
        // A) Verificar si tiene palabras de exclusión (enviaste, pagaste, compraste, codigo, etc.)
        val hasExclusion = ColombianPaymentKeywords.EXCLUSION_KEYWORDS.any { normalized.contains(it) }
        if (hasExclusion) {
            return FilterResult.Rejected(RejectionReason.EXCLUSION_KEYWORD_FOUND)
        }

        // B) Verificar si tiene palabras de pago recibido (recibiste, te enviaron, te llego, etc.)
        val hasIncoming = ColombianPaymentKeywords.INCOMING_PAYMENT_KEYWORDS.any { normalized.contains(it) }
        if (!hasIncoming) {
            return FilterResult.Rejected(RejectionReason.NO_INCOMING_KEYWORD_FOUND)
        }

        // 3. Monto válido
        val amount = parser.extractAmount(title, text)
        if (amount == null || amount <= 0) {
            return FilterResult.Rejected(RejectionReason.INVALID_OR_MISSING_AMOUNT)
        }

        // 4. No duplicada en ventana de 2 minutos
        // Limpiar detecciones viejas (> 2 minutos)
        recentDetections.removeAll { now - it.timestamp > DUPLICATE_WINDOW_MILLIS }
        val isDuplicate = recentDetections.any {
            it.packageName.equals(packageName, ignoreCase = true) &&
                    it.amount == amount &&
                    (now - it.timestamp) <= DUPLICATE_WINDOW_MILLIS
        }
        if (isDuplicate) {
            return FilterResult.Rejected(RejectionReason.DUPLICATE_WITHIN_WINDOW)
        }

        // 5. Notificación reciente
        // Si el postTime viene de hace más de 3 minutos, rechazar (evita replay al encender)
        if (notificationPostTimeMillis > 0 && (now - notificationPostTimeMillis) > MAX_NOTIFICATION_AGE_MILLIS) {
            return FilterResult.Rejected(RejectionReason.NOTIFICATION_TOO_OLD)
        }

        // Extraer nombre del remitente si está presente en la notificación
        val senderName = parser.extractSenderName(title, text)

        // Pago aceptado -> Registrar para evitar duplicados en la ventana de 2 minutos
        recentDetections.add(RecentDetection(packageName, amount, now))
        return FilterResult.Accepted(amount = amount, senderName = senderName)
    }

    @Synchronized
    fun clearRecentDetections() {
        recentDetections.clear()
    }
}
