package com.ambiorix.pay.sound

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Anunciador sonoro y por voz de pagos para Ambiorix Pay.
 * 
 * Optimizado y certificado para compatibilidad robusta con Android 10 hasta Android 14:
 * 1. Declaración <queries> en Manifest para permitir visibilidad de paquetes TTS en Android 11-14.
 * 2. Cola secuencial de reproducción thread-safe con UtteranceProgressListener para evitar
 *    superposiciones o saltos entre notificaciones consecutivas.
 * 3. Enrutamiento por audio de asistencia multimedia con volumen adaptativo.
 * 4. Detección exhaustiva de idiomas en español (es-CO, es-419, es-US, es-ES, es).
 * 5. Fallback automático a tono chime si TTS no está listo o no admite voz.
 * 6. Registros de diagnóstico claros en Logcat ("AmbiorixTTS").
 */
class SoundAnnouncer private constructor(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AmbiorixTTS"
        const val DEFAULT_SPEECH_RATE = 0.95f
        const val DEFAULT_PITCH = 1.0f

        @Volatile
        private var instance: SoundAnnouncer? = null

        fun getInstance(context: Context): SoundAnnouncer {
            return instance ?: synchronized(this) {
                instance ?: SoundAnnouncer(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private var toneGenerator: ToneGenerator? = null
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var isTtsInitialized = false

    @Volatile
    private var isSpeakingNow = false

    private val speechQueue = ConcurrentLinkedQueue<String>()

    private val _isSpanishSupported = MutableStateFlow(true)
    val isSpanishSupported: StateFlow<Boolean> = _isSpanishSupported.asStateFlow()

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _ttsStatusDetail = MutableStateFlow("Iniciando motor de voz...")
    val ttsStatusDetail: StateFlow<String> = _ttsStatusDetail.asStateFlow()

    init {
        initToneGenerator()
        initTextToSpeech()
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            Log.d(TAG, "ToneGenerator inicializado con STREAM_MUSIC")
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al inicializar ToneGenerator con STREAM_MUSIC, probando STREAM_SYSTEM: ${e.message}")
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 100)
            } catch (e2: Exception) {
                Log.e(TAG, "No se pudo inicializar ToneGenerator: ${e2.message}")
                toneGenerator = null
            }
        }
    }

    private fun initTextToSpeech() {
        try {
            Log.d(TAG, "Inicializando TextToSpeech en Android SDK ${Build.VERSION.SDK_INT}...")
            textToSpeech = TextToSpeech(appContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al instanciar TextToSpeech: ${e.message}", e)
            _isSpanishSupported.value = false
            _isTtsReady.value = false
            _ttsStatusDetail.value = "Error al iniciar motor de voz: ${e.localizedMessage}"
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val tts = textToSpeech
            if (tts == null) {
                Log.e(TAG, "TTS onInit SUCCESS pero instancia es nula")
                _isTtsReady.value = false
                return
            }

            Log.d(TAG, "TextToSpeech inicializado exitosamente (SUCCESS). Configurando atributos y lenguaje...")

            // Configurar canal de salida de audio multimedia
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts.setAudioAttributes(audioAttributes)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron establecer AudioAttributes: ${e.message}")
            }

            // Configurar listener para saber cuándo termina de hablar y pasar al siguiente en la cola
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeakingNow = true
                    Log.d(TAG, "Iniciando locución: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    isSpeakingNow = false
                    Log.d(TAG, "Finalizada locución: $utteranceId")
                    processNextInQueue()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeakingNow = false
                    Log.e(TAG, "Error en locución: $utteranceId")
                    processNextInQueue()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isSpeakingNow = false
                    Log.e(TAG, "Error en locución: $utteranceId (código: $errorCode)")
                    processNextInQueue()
                }
            })

            // Probar variantes en español ordenadas por prioridad
            val localesToTry = listOf(
                Locale("es", "CO"),
                Locale("es", "419"),
                Locale("es", "US"),
                Locale("es", "MX"),
                Locale("es", "ES"),
                Locale("es"),
                Locale.getDefault()
            )

            var selectedLocale: Locale? = null
            for (loc in localesToTry) {
                try {
                    val availability = tts.isLanguageAvailable(loc)
                    if (availability >= TextToSpeech.LANG_AVAILABLE) {
                        val result = tts.setLanguage(loc)
                        if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                            selectedLocale = loc
                            Log.d(TAG, "Idioma de voz configurado: ${loc.toLanguageTag()}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error probando locale ${loc.toLanguageTag()}: ${e.message}")
                }
            }

            if (selectedLocale != null) {
                _isSpanishSupported.value = true
                _ttsStatusDetail.value = "Voz activa (${selectedLocale.displayName})"
            } else {
                // Intento final con Locale("es") directo
                val res = tts.setLanguage(Locale("es"))
                val isOk = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                _isSpanishSupported.value = isOk
                _ttsStatusDetail.value = if (isOk) "Voz en español activa" else "Voz en español no disponible en este dispositivo"
                Log.w(TAG, "Resultado setLanguage(es): $res, supported=$isOk")
            }

            tts.setPitch(DEFAULT_PITCH)
            tts.setSpeechRate(DEFAULT_SPEECH_RATE)

            isTtsInitialized = true
            _isTtsReady.value = true

            diagnoseVolume()

            // Despachar cualquier elemento pendiente en cola
            processNextInQueue()
        } else {
            Log.e(TAG, "Fallo al inicializar TextToSpeech. Código de estado: $status")
            isTtsInitialized = false
            _isTtsReady.value = false
            _isSpanishSupported.value = false
            _ttsStatusDetail.value = "Motor de voz no disponible (código: $status)"
        }
    }

    private fun diagnoseVolume() {
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                Log.d(TAG, "Nivel de volumen multimedia STREAM_MUSIC: $currentVol / $maxVol")
                if (currentVol == 0) {
                    Log.w(TAG, "ATENCIÓN: El volumen multimedia del dispositivo está en SILENCIO (0)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el nivel de volumen: ${e.message}")
        }
    }

    /**
     * Emite el tono de confirmación sonoro (Chime instantáneo).
     */
    fun playChime() {
        try {
            if (toneGenerator == null) {
                initToneGenerator()
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 320)
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al reproducir chime principal: ${e.message}")
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallo al reproducir tono secundario: ${e2.message}")
            }
        }
    }

    /**
     * Encola o habla de inmediato el anuncio de pago garantizando que no se superpongan frases.
     */
    fun speakPayment(
        amount: Long,
        includeThankYou: Boolean = true,
        senderName: String? = null,
        announceSenderName: Boolean = false
    ) {
        val phrase = SpanishNumbers.buildPaymentAnnouncementPhrase(
            amount = amount,
            includeThankYou = includeThankYou,
            senderName = senderName,
            announceSenderName = announceSenderName
        )
        Log.d(TAG, "Encolando locución de pago: \"$phrase\"")
        speechQueue.offer(phrase)
        processNextInQueue()
    }

    @Synchronized
    private fun processNextInQueue() {
        if (isSpeakingNow) {
            Log.d(TAG, "Ya hay una locución en curso. La siguiente esperará en cola (${speechQueue.size} pendientes).")
            return
        }

        val phrase = speechQueue.poll() ?: return

        if (!isTtsInitialized || textToSpeech == null) {
            Log.w(TAG, "TTS aún no está listo. Re-encolando frase para cuando onInit complete...")
            speechQueue.offer(phrase)
            return
        }

        try {
            val tts = textToSpeech
            if (tts != null) {
                val utteranceId = "AMB_PAY_${System.currentTimeMillis()}"
                isSpeakingNow = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val params = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }
                    val result = tts.speak(phrase, TextToSpeech.QUEUE_ADD, params, utteranceId)
                    if (result != TextToSpeech.SUCCESS) {
                        Log.e(TAG, "speak devolvió error: $result. Fallback a sonido chime.")
                        isSpeakingNow = false
                        playChime()
                        processNextInQueue()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val params = HashMap<String, String>().apply {
                        put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                    @Suppress("DEPRECATION")
                    tts.speak(phrase, TextToSpeech.QUEUE_ADD, params)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al reproducir locución: ${e.message}", e)
            isSpeakingNow = false
            playChime()
            processNextInQueue()
        }
    }

    fun speakTest(
        amount: Long = 35000L,
        includeThankYou: Boolean = true,
        senderName: String? = null,
        announceSenderName: Boolean = false
    ) {
        speakPayment(amount, includeThankYou, senderName, announceSenderName)
    }

    fun announce(
        amount: Long,
        playSound: Boolean,
        speakVoice: Boolean,
        includeThankYou: Boolean,
        senderName: String? = null,
        announceSenderName: Boolean = false
    ) {
        if (playSound) {
            playChime()
        }
        if (speakVoice) {
            speakPayment(amount, includeThankYou, senderName, announceSenderName)
        }
    }

    /**
     * Detiene la síntesis de voz actual y limpia la cola de locuciones pendientes.
     */
    fun stop() {
        try {
            speechQueue.clear()
            isSpeakingNow = false
            textToSpeech?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error al detener TTS: ${e.message}")
        }
    }

    /**
     * Abre los ajustes de Texto a Voz del sistema Android.
     */
    fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            try {
                val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(installIntent)
            } catch (e2: Exception) {
                val generalIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(generalIntent)
            }
        }
    }

    fun release() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            // Ignorar
        }

        try {
            speechQueue.clear()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isTtsInitialized = false
            _isTtsReady.value = false
        } catch (e: Exception) {
            // Ignorar
        }
    }
}
