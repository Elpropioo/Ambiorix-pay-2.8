package com.ambiorix.pay.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val isServiceEnabled: Boolean = true,
    val businessHours: BusinessHoursConfig = BusinessHoursConfig(),
    val businessName: String = "Mi Negocio",
    val logoPath: String? = null,
    val scanToPayText: String = "ESCANEA PARA PAGAR",
    val phoneText: String = "",
    val additionalText: String = "Verifica el nombre del negocio antes de transferir",
    val qr1Path: String? = null,
    val qr2Path: String? = null,
    val qr2Enabled: Boolean = false,
    val pinCode: String = "1234",
    val confirmationDurationSeconds: Int = 7,
    val soundEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val includeThankYou: Boolean = true,
    val announceSenderName: Boolean = true,     // Anunciar por voz primer nombre y primer apellido
    val smsPaymentEnabled: Boolean = true,       // Detección automática de SMS de transacciones Nequi
    // Comunicación entre tiendas por SMS P2P
    val peerStoreEnabled: Boolean = false,
    val peerStoreName: String = "Tienda Sur",
    val peerStoreId: String = "STORE_02",
    val peerStorePhone: String = "",
    val peerSharedSecret: String = "ambiorix_p2p_secret_key_2026",
    val localStoreId: String = "STORE_01",
    val localStoreName: String = "Tienda Norte",
    val enabledPackages: Set<String> = KnownApps.DEFAULT_ENABLED_PACKAGES,
    val customPackages: Set<String> = emptySet(),
    val screenPinningNoticeDismissed: Boolean = false
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    fun getSettings(): AppSettings = _settingsFlow.value

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settingsFlow.value)
        saveSettings(updated)
        _settingsFlow.value = updated
    }

    private fun loadSettings(): AppSettings {
        val enabledPkgs = prefs.getStringSet(KEY_ENABLED_PACKAGES, null)
            ?: KnownApps.DEFAULT_ENABLED_PACKAGES
        val customPkgs = prefs.getStringSet(KEY_CUSTOM_PACKAGES, emptySet()) ?: emptySet()
        val hoursJson = prefs.getString(KEY_BUSINESS_HOURS, null)

        return AppSettings(
            isServiceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, true),
            businessHours = BusinessHoursConfig.fromJson(hoursJson),
            businessName = prefs.getString(KEY_BUSINESS_NAME, "Mi Negocio") ?: "Mi Negocio",
            logoPath = prefs.getString(KEY_LOGO_PATH, null),
            scanToPayText = prefs.getString(KEY_SCAN_TO_PAY_TEXT, "ESCANEA PARA PAGAR")
                ?: "ESCANEA PARA PAGAR",
            phoneText = prefs.getString(KEY_PHONE_TEXT, "") ?: "",
            additionalText = prefs.getString(
                KEY_ADDITIONAL_TEXT,
                "Verifica el nombre del negocio antes de transferir"
            ) ?: "Verifica el nombre del negocio antes de transferir",
            qr1Path = prefs.getString(KEY_QR1_PATH, null),
            qr2Path = prefs.getString(KEY_QR2_PATH, null),
            qr2Enabled = prefs.getBoolean(KEY_QR2_ENABLED, false),
            pinCode = prefs.getString(KEY_PIN_CODE, "1234") ?: "1234",
            confirmationDurationSeconds = prefs.getInt(KEY_CONFIRMATION_DURATION, 7)
                .coerceIn(3, 30),
            soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true),
            ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, true),
            includeThankYou = prefs.getBoolean(KEY_INCLUDE_THANK_YOU, true),
            announceSenderName = prefs.getBoolean(KEY_ANNOUNCE_SENDER_NAME, true),
            smsPaymentEnabled = prefs.getBoolean(KEY_SMS_PAYMENT_ENABLED, true),
            peerStoreEnabled = prefs.getBoolean(KEY_PEER_STORE_ENABLED, false),
            peerStoreName = prefs.getString(KEY_PEER_STORE_NAME, "Tienda Sur") ?: "Tienda Sur",
            peerStoreId = prefs.getString(KEY_PEER_STORE_ID, "STORE_02") ?: "STORE_02",
            peerStorePhone = prefs.getString(KEY_PEER_STORE_PHONE, "") ?: "",
            peerSharedSecret = prefs.getString(KEY_PEER_SHARED_SECRET, "ambiorix_p2p_secret_key_2026") ?: "ambiorix_p2p_secret_key_2026",
            localStoreId = prefs.getString(KEY_LOCAL_STORE_ID, "STORE_01") ?: "STORE_01",
            localStoreName = prefs.getString(KEY_LOCAL_STORE_NAME, "Tienda Norte") ?: "Tienda Norte",
            enabledPackages = enabledPkgs,
            customPackages = customPkgs,
            screenPinningNoticeDismissed = prefs.getBoolean(KEY_SCREEN_PINNING_DISMISSED, false)
        )
    }

    private fun saveSettings(s: AppSettings) {
        prefs.edit().apply {
            putBoolean(KEY_SERVICE_ENABLED, s.isServiceEnabled)
            putString(KEY_BUSINESS_HOURS, s.businessHours.toJson())
            putString(KEY_BUSINESS_NAME, s.businessName)
            putString(KEY_LOGO_PATH, s.logoPath)
            putString(KEY_SCAN_TO_PAY_TEXT, s.scanToPayText)
            putString(KEY_PHONE_TEXT, s.phoneText)
            putString(KEY_ADDITIONAL_TEXT, s.additionalText)
            putString(KEY_QR1_PATH, s.qr1Path)
            putString(KEY_QR2_PATH, s.qr2Path)
            putBoolean(KEY_QR2_ENABLED, s.qr2Enabled)
            putString(KEY_PIN_CODE, s.pinCode)
            putInt(KEY_CONFIRMATION_DURATION, s.confirmationDurationSeconds.coerceIn(3, 30))
            putBoolean(KEY_SOUND_ENABLED, s.soundEnabled)
            putBoolean(KEY_TTS_ENABLED, s.ttsEnabled)
            putBoolean(KEY_INCLUDE_THANK_YOU, s.includeThankYou)
            putBoolean(KEY_ANNOUNCE_SENDER_NAME, s.announceSenderName)
            putBoolean(KEY_SMS_PAYMENT_ENABLED, s.smsPaymentEnabled)
            putBoolean(KEY_PEER_STORE_ENABLED, s.peerStoreEnabled)
            putString(KEY_PEER_STORE_NAME, s.peerStoreName)
            putString(KEY_PEER_STORE_ID, s.peerStoreId)
            putString(KEY_PEER_STORE_PHONE, s.peerStorePhone)
            putString(KEY_PEER_SHARED_SECRET, s.peerSharedSecret)
            putString(KEY_LOCAL_STORE_ID, s.localStoreId)
            putString(KEY_LOCAL_STORE_NAME, s.localStoreName)
            putStringSet(KEY_ENABLED_PACKAGES, s.enabledPackages)
            putStringSet(KEY_CUSTOM_PACKAGES, s.customPackages)
            putBoolean(KEY_SCREEN_PINNING_DISMISSED, s.screenPinningNoticeDismissed)
            apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "ambiorix_pay_settings"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_BUSINESS_HOURS = "business_hours_json"
        private const val KEY_BUSINESS_NAME = "business_name"
        private const val KEY_LOGO_PATH = "logo_path"
        private const val KEY_SCAN_TO_PAY_TEXT = "scan_to_pay_text"
        private const val KEY_PHONE_TEXT = "phone_text"
        private const val KEY_ADDITIONAL_TEXT = "additional_text"
        private const val KEY_QR1_PATH = "qr1_path"
        private const val KEY_QR2_PATH = "qr2_path"
        private const val KEY_QR2_ENABLED = "qr2_enabled"
        private const val KEY_PIN_CODE = "pin_code"
        private const val KEY_CONFIRMATION_DURATION = "confirmation_duration"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_INCLUDE_THANK_YOU = "include_thank_you"
        private const val KEY_ANNOUNCE_SENDER_NAME = "announce_sender_name"
        private const val KEY_SMS_PAYMENT_ENABLED = "sms_payment_enabled"
        private const val KEY_PEER_STORE_ENABLED = "peer_store_enabled"
        private const val KEY_PEER_STORE_NAME = "peer_store_name"
        private const val KEY_PEER_STORE_ID = "peer_store_id"
        private const val KEY_PEER_STORE_PHONE = "peer_store_phone"
        private const val KEY_PEER_SHARED_SECRET = "peer_shared_secret"
        private const val KEY_LOCAL_STORE_ID = "local_store_id"
        private const val KEY_LOCAL_STORE_NAME = "local_store_name"
        private const val KEY_ENABLED_PACKAGES = "enabled_packages"
        private const val KEY_CUSTOM_PACKAGES = "custom_packages"
        private const val KEY_SCREEN_PINNING_DISMISSED = "screen_pinning_dismissed"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
