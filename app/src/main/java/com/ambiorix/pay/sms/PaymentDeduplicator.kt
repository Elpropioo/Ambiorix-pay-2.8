package com.ambiorix.pay.sms

import android.util.Log
import com.ambiorix.pay.data.Payment
import java.security.MessageDigest

/**
 * Gestor de deduplicación de pagos y transacciones en Ambiorix Pay.
 * 
 * Funciones clave:
 * 1. Deduplicación estricta de SMS idénticos o re-entregados por Android mediante huella criptográfica SHA-256.
 * 2. Deduplicación cruzada entre Notificaciones Push del sistema y mensajes SMS de Nequi recibidos
 *    en una ventana cercana de tiempo (evita doble pantalla verde y doble voz para la misma transferencia).
 * 3. Conservación de pagos legítimos distintos aunque tengan el mismo valor.
 */
object PaymentDeduplicator {

    private const val TAG = "PaymentDeduplicator"

    // Ventana de tiempo para descartar SMS idénticos repetidos (15 minutos)
    private const val SMS_DEDUP_WINDOW_MS = 15 * 60 * 1000L

    // Ventana de tiempo para vincular Notificación Push y SMS de la misma transacción (45 segundos)
    private const val CROSS_SOURCE_WINDOW_MS = 45 * 1000L

    // Máximo número de huellas en memoria
    private const val MAX_ENTRIES = 250

    // Registro de huellas de SMS procesados: Fingerprint -> Timestamp
    private val processedSmsFingerprints = object : LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    // Registro de pagos recientes por origen para deduplicación cruzada:
    // Monto -> Lista de eventos recientes (timestamp, senderName, channel)
    private data class RecentEvent(val amount: Long, val timestamp: Long, val senderName: String?, val isSms: Boolean)
    private val recentEvents = mutableListOf<RecentEvent>()

    /**
     * Calcula una huella digital única (SHA-256) para el mensaje SMS basada en sus datos intrínsecos.
     */
    fun computeSmsFingerprint(
        amount: Long,
        senderName: String,
        receiverBusiness: String?,
        dateTimeText: String?,
        channel: String?,
        rawBody: String
    ): String {
        val payload = "$amount|$senderName|${receiverBusiness.orEmpty()}|${dateTimeText.orEmpty()}|${channel.orEmpty()}|${rawBody.trim()}"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(payload.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            payload.hashCode().toString()
        }
    }

    /**
     * Verifica si un SMS ya fue procesado anteriormente dentro de la ventana de deduplicación.
     */
    @Synchronized
    fun isDuplicateSms(fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
        cleanupExpired(now)
        val lastSeen = processedSmsFingerprints[fingerprint]
        if (lastSeen != null && (now - lastSeen) < SMS_DEDUP_WINDOW_MS) {
            Log.d(TAG, "SMS duplicado detectado (huella ya procesada hace ${(now - lastSeen) / 1000}s). Se descarta.")
            return true
        }
        return false
    }

    /**
     * Registra un SMS como procesado de forma permanente en la caché de deduplicación.
     */
    @Synchronized
    fun markSmsProcessed(fingerprint: String, amount: Long, senderName: String?, now: Long = System.currentTimeMillis()) {
        processedSmsFingerprints[fingerprint] = now
        recentEvents.add(RecentEvent(amount, now, senderName, isSms = true))
        cleanupExpired(now)
    }

    /**
     * Verifica si un SMS entrante corresponde a una notificación Push ya procesada hace pocos segundos
     * para el mismo monto, evitando duplicar el anuncio.
     */
    @Synchronized
    fun isDuplicateCrossSourceForSms(amount: Long, now: Long = System.currentTimeMillis()): Boolean {
        cleanupExpired(now)
        val match = recentEvents.find { !it.isSms && it.amount == amount && (now - it.timestamp) < CROSS_SOURCE_WINDOW_MS }
        if (match != null) {
            Log.d(TAG, "Deduplicación cruzada: SMS para monto $amount coincide con Notificación Push procesada hace ${(now - match.timestamp) / 1000}s.")
            return true
        }
        return false
    }

    /**
     * Registra una Notificación Push recibida para prevenir duplicación con SMS subsecuente.
     */
    @Synchronized
    fun recordPushPayment(amount: Long, now: Long = System.currentTimeMillis()) {
        recentEvents.add(RecentEvent(amount, now, senderName = null, isSms = false))
        cleanupExpired(now)
    }

    /**
     * Verifica si una Notificación Push entrante corresponde a un SMS que ya anunció la transacción.
     */
    @Synchronized
    fun isDuplicateCrossSourceForPush(amount: Long, now: Long = System.currentTimeMillis()): Boolean {
        cleanupExpired(now)
        val match = recentEvents.find { it.isSms && it.amount == amount && (now - it.timestamp) < CROSS_SOURCE_WINDOW_MS }
        if (match != null) {
            Log.d(TAG, "Deduplicación cruzada: Notificación Push para monto $amount coincide con SMS ya procesado hace ${(now - match.timestamp) / 1000}s.")
            return true
        }
        return false
    }

    @Synchronized
    private fun cleanupExpired(now: Long) {
        val smsThreshold = now - SMS_DEDUP_WINDOW_MS
        val iterator = processedSmsFingerprints.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < smsThreshold) {
                iterator.remove()
            }
        }

        val crossThreshold = now - CROSS_SOURCE_WINDOW_MS
        recentEvents.removeAll { it.timestamp < crossThreshold }
    }

    /**
     * Reinicia la caché de deduplicación (usado principalmente en pruebas unitarias).
     */
    @Synchronized
    fun clearCacheForTesting() {
        processedSmsFingerprints.clear()
        recentEvents.clear()
    }
}
