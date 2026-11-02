package com.ambiorix.pay.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class PaymentRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isLoadingFlow = MutableStateFlow(true)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private val _errorFlow = MutableStateFlow<String?>(null)
    val errorFlow: StateFlow<String?> = _errorFlow.asStateFlow()

    private val _paymentsFlow = MutableStateFlow<List<Payment>>(emptyList())
    val paymentsFlow: StateFlow<List<Payment>> = _paymentsFlow.asStateFlow()

    init {
        reloadFromStorage()
    }

    @Synchronized
    fun reloadFromStorage() {
        _isLoadingFlow.value = true
        _errorFlow.value = null
        try {
            _paymentsFlow.value = loadPayments()
        } catch (e: Exception) {
            _errorFlow.value = "No se pudo leer el historial local: ${e.localizedMessage ?: "Error de lectura"}"
        } finally {
            _isLoadingFlow.value = false
        }
    }

    @Synchronized
    fun recordPayment(payment: Payment) {
        val current = _paymentsFlow.value.toMutableList()

        // Evitar insertar dos veces el mismo pago o notificación duplicada inmediata
        val isDuplicate = current.any { existing ->
            (existing.id.isNotBlank() && existing.id == payment.id) ||
                    (existing.packageName.equals(payment.packageName, ignoreCase = true) &&
                            existing.amount == payment.amount &&
                            existing.senderName.orEmpty().equals(payment.senderName.orEmpty(), ignoreCase = true) &&
                            abs(existing.timestampMillis - payment.timestampMillis) <= 2000L)
        }
        if (isDuplicate) {
            return
        }

        current.add(0, payment)
        val sorted = current.sortedByDescending { it.timestampMillis }
        val trimmed = if (sorted.size > MAX_HISTORY_SIZE) sorted.take(MAX_HISTORY_SIZE) else sorted
        _paymentsFlow.value = trimmed
        savePayments(trimmed)
    }

    @Synchronized
    fun clearHistory() {
        _paymentsFlow.value = emptyList()
        _errorFlow.value = null
        prefs.edit().remove(KEY_PAYMENTS_JSON).apply()
    }

    private fun loadPayments(): List<Payment> {
        val jsonStr = prefs.getString(KEY_PAYMENTS_JSON, null) ?: return emptyList()
        val list = mutableListOf<Payment>()
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val sourceStr = obj.optString("source", "NOTIFICATION")
            val sourceEnum = try {
                PaymentSource.valueOf(sourceStr)
            } catch (e: Exception) {
                PaymentSource.NOTIFICATION
            }

            val timestampMillis = obj.getLong("timestampMillis")
            val amount = obj.getLong("amount")
            val packageName = obj.getString("packageName")
            val rawId = obj.optString("id").trim()
            val stableId = if (rawId.isNotEmpty()) {
                rawId
            } else {
                "legacy_${timestampMillis}_${amount}_${packageName.hashCode()}"
            }

            val txDateTimeText = if (obj.has("transactionDateTimeText")) {
                obj.optString("transactionDateTimeText").takeIf { it.isNotBlank() }
            } else {
                null
            }

            // Migración segura para registros de versiones anteriores (2.6 GT):
            // Si el campo hasExactSeconds existe, usarlo; si es un registro antiguo sin ese campo,
            // conservar segundos si el timestamp tiene segundos o si no provino de texto truncado a minutos.
            val hasExactSeconds = if (obj.has("hasExactSeconds")) {
                obj.optBoolean("hasExactSeconds", true)
            } else {
                val hasSubMinuteMillis = (timestampMillis % 60_000L) != 0L
                hasSubMinuteMillis || txDateTimeText == null
            }

            list.add(
                Payment(
                    id = stableId,
                    amount = amount,
                    appName = obj.getString("appName"),
                    packageName = packageName,
                    timestampMillis = timestampMillis,
                    senderName = if (obj.has("senderName")) obj.optString("senderName").takeIf { it.isNotBlank() } else null,
                    channel = if (obj.has("channel")) obj.optString("channel").takeIf { it.isNotBlank() } else null,
                    receiverBusiness = if (obj.has("receiverBusiness")) obj.optString("receiverBusiness").takeIf { it.isNotBlank() } else null,
                    transactionDateTimeText = txDateTimeText,
                    source = sourceEnum,
                    hasExactSeconds = hasExactSeconds
                )
            )
        }
        return list.sortedByDescending { it.timestampMillis }
    }

    private fun savePayments(payments: List<Payment>) {
        try {
            val array = JSONArray()
            for (p in payments) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("amount", p.amount)
                    put("appName", p.appName)
                    put("packageName", p.packageName)
                    put("timestampMillis", p.timestampMillis)
                    if (p.senderName != null) put("senderName", p.senderName)
                    if (p.channel != null) put("channel", p.channel)
                    if (p.receiverBusiness != null) put("receiverBusiness", p.receiverBusiness)
                    if (p.transactionDateTimeText != null) put("transactionDateTimeText", p.transactionDateTimeText)
                    put("source", p.source.name)
                    put("hasExactSeconds", p.hasExactSeconds)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_PAYMENTS_JSON, array.toString()).apply()
        } catch (e: Exception) {
            _errorFlow.value = "Error al guardar historial local: ${e.localizedMessage ?: "Error de escritura"}"
        }
    }

    companion object {
        private const val PREFS_NAME = "ambiorix_pay_history"
        private const val KEY_PAYMENTS_JSON = "payments_json"
        private const val MAX_HISTORY_SIZE = 1000

        @Volatile
        private var INSTANCE: PaymentRepository? = null

        fun getInstance(context: Context): PaymentRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PaymentRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
