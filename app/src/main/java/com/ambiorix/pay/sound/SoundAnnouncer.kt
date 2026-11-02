package com.ambiorix.pay.sound

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Anunciador sonoro y por voz de pagos para Ambiorix Pay.
 *
 * Optimizado contra trabas, cortes o voz entrecortada en todo tipo de celulares Android (10 a 14+):
 * 1. Canal multimedia de buffer profundo (USAGE_MEDIA + STREAM_MUSIC) en lugar de
 *    USAGE_ASSISTANCE_SONIFICATION (que en Samsung/Xiaomi/Motorola/MediaTek corta frases largas).
 * 2. Selección prioritaria de voz OFFLINE local (!voice.isNetworkConnectionRequired) y motor
 *    Google TTS cuando está instalado, evitando que la voz se trabe por latencia de red.
 * 3. Velocidad nativa 1.0f sin re-muestreo DSP (evita distorsión y trabas de CPU en gama media/baja).
 * 4. Normalización fonética continua sin puntos ni comas intermedias que dividan el AudioTrack
 *    o disparen onDone prematuramente a mitad de la frase.
 * 5. Secuenciación limpia entre el timbre (Chime) y la voz en un hilo dedicado de audio
 *    (HandlerThread con THREAD_PRIORITY_AUDIO) para que ToneGenerator y TextToSpeech nunca
 *    colisionen en el mezclador de hardware.
 * 6. Gestión de AudioFocus y Watchdog anti-bloqueo de cola.
 */
class SoundAnnouncer private constructor(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AmbiorixTTS"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        private const val CHIME_DURATION_MS = 220
        private const val POST_CHIME_VOICE_DELAY_MS = 280L
        private const val UTTERANCE_WATCHDOG_TIMEOUT_MS = 7000L

        @Volatile
        private var instance: SoundAnnouncer? = null

        fun getInstance(context: Context): SoundAnnouncer {
            return instance ?: synchronized(this) {
                instance ?: SoundAnnouncer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Normaliza la frase en un flujo fonético continuo sin puntos ni comas intermedias.
         * En muchos motores TTS OEM (Samsung, Xiaomi, Motorola, MediaTek), un punto '.' o coma ','
         * en medio de la cadena divide la síntesis en múltiples buffers internos, provocando que
         * la voz se trabe o se corte al pasar a "Gracias por tu compra".
         */
        fun formatPhraseForFluidSpeech(rawPhrase: String): String {
            return rawPhrase
                .replace(Regex("\\.\\s+Gracias", RegexOption.IGNORE_CASE), " gracias")
                .replace(",", " ")
                .replace(".", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioThread = HandlerThread("AmbiorixAudioWorker", Process.THREAD_PRIORITY_AUDIO).apply {
        start()
    }
    private val audioHandler = Handler(audioThread.looper)

    private var toneGenerator: ToneGenerator? = null
    private var textToSpeech: TextToSpeech? = null
    private var triedFallbackDefaultEngine = false

    @Volatile
    private var isTtsInitialized = false

    @Volatile
    private var isSpeakingNow = false

    private val speechQueue = ConcurrentLinkedQueue<String>()

    private val watchdogRunnable = Runnable {
        if (isSpeakingNow) {
            Log.w(TAG, "Watchdog TTS activado: liberando cola tras tiempo máximo de locución.")
            isSpeakingNow = false
            abandonAudioFocusSafely()
            processNextInQueue()
        }
    }

    private val _isSpanishSupported = MutableStateFlow(true)
    val isSpanishSupported: StateFlow<Boolean> = _isSpanishSupported.asStateFlow()

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _ttsStatusDetail = MutableStateFlow("Iniciando motor de voz...")
    val ttsStatusDetail: StateFlow<String> = _ttsStatusDetail.asStateFlow()

    init {
        audioHandler.post {
            initToneGenerator()
            initTextToSpeech(preferGoogleEngine = true)
        }
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
            Log.d(TAG, "ToneGenerator inicializado con STREAM_MUSIC")
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al inicializar ToneGenerator con STREAM_MUSIC, probando STREAM_NOTIFICATION: ${e.message}")
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
            } catch (e2: Exception) {
                Log.e(TAG, "No se pudo inicializar ToneGenerator: ${e2.message}")
                toneGenerator = null
            }
        }
    }

    private fun isGoogleTtsInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    GOOGLE_TTS_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(GOOGLE_TTS_PACKAGE, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun initTextToSpeech(preferGoogleEngine: Boolean) {
        try {
            val useGoogle = preferGoogleEngine && isGoogleTtsInstalled()
            Log.d(TAG, "Inicializando TextToSpeech (useGoogle=$useGoogle) en SDK ${Build.VERSION.SDK_INT}...")
            textToSpeech = if (useGoogle) {
                TextToSpeech(appContext, this, GOOGLE_TTS_PACKAGE)
            } else {
                TextToSpeech(appContext, this)
            }
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
                _isTtsReady.value = false
                return
            }

            // Usar USAGE_MEDIA + CONTENT_TYPE_SPEECH para tener buffer de audio continuo de alta capacidad
            // (evita que la voz se trabe a mitad de frase como ocurre con USAGE_ASSISTANCE_SONIFICATION)
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts.setAudioAttributes(audioAttributes)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron establecer AudioAttributes: ${e.message}")
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeakingNow = true
                    audioHandler.removeCallbacks(watchdogRunnable)
                    audioHandler.postDelayed(watchdogRunnable, UTTERANCE_WATCHDOG_TIMEOUT_MS)
                }

                override fun onDone(utteranceId: String?) {
                    audioHandler.removeCallbacks(watchdogRunnable)
                    isSpeakingNow = false
                    audioHandler.post {
                        if (speechQueue.isEmpty()) {
                            abandonAudioFocusSafely()
                        } else {
                            processNextInQueue()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    audioHandler.removeCallbacks(watchdogRunnable)
                    isSpeakingNow = false
                    audioHandler.post {
                        if (speechQueue.isEmpty()) {
                            abandonAudioFocusSafely()
                        } else {
                            processNextInQueue()
                        }
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    audioHandler.removeCallbacks(watchdogRunnable)
                    isSpeakingNow = false
                    Log.e(TAG, "Error en locución: $utteranceId (código: $errorCode)")
                    audioHandler.post {
                        if (speechQueue.isEmpty()) {
                            abandonAudioFocusSafely()
                        } else {
                            processNextInQueue()
                        }
                    }
                }
            })

            val localesToTry = listOf(
                Locale("es", "CO"),
                Locale("es", "419"),
                Locale("es", "MX"),
                Locale("es", "US"),
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
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error probando locale ${loc.toLanguageTag()}: ${e.message}")
                }
            }

            // Seleccionar explícitamente una voz OFFLINE local para evitar trabas por red lenta/inestable
            val offlineVoiceSelected = selectBestOfflineSpanishVoice(tts, selectedLocale)

            if (selectedLocale != null || offlineVoiceSelected) {
                _isSpanishSupported.value = true
                val localeLabel = selectedLocale?.displayName ?: "Español"
                _ttsStatusDetail.value = if (offlineVoiceSelected) {
                    "Voz fluida offline activa ($localeLabel)"
                } else {
                    "Voz activa ($localeLabel)"
                }
            } else {
                val res = tts.setLanguage(Locale("es"))
                val isOk = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                _isSpanishSupported.value = isOk
                _ttsStatusDetail.value = if (isOk) {
                    "Voz en español activa"
                } else {
                    "Voz en español no disponible en este dispositivo"
                }
            }

            // Velocidad 1.0f nativa para evitar distorsión/trabas por time-stretching en CPU
            tts.setPitch(DEFAULT_PITCH)
            tts.setSpeechRate(DEFAULT_SPEECH_RATE)

            isTtsInitialized = true
            _isTtsReady.value = true

            diagnoseVolume()
            audioHandler.post { processNextInQueue() }
        } else {
            if (!triedFallbackDefaultEngine) {
                triedFallbackDefaultEngine = true
                Log.w(TAG, "Reintentando inicialización TTS con motor predeterminado del sistema...")
                audioHandler.post { initTextToSpeech(preferGoogleEngine = false) }
                return
            }
            Log.e(TAG, "Fallo al inicializar TextToSpeech. Código de estado: $status")
            isTtsInitialized = false
            _isTtsReady.value = false
            _isSpanishSupported.value = false
            _ttsStatusDetail.value = "Motor de voz no disponible (código: $status)"
        }
    }

    /**
     * Busca y fija una voz en español instalada localmente (!isNetworkConnectionRequired)
     * para que el celular sintetice toda la frase en memoria RAM sin pausas ni trabas de red.
     */
    private fun selectBestOfflineSpanishVoice(tts: TextToSpeech, preferredLocale: Locale?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val availableVoices = tts.voices ?: return false
            val spanishVoices = availableVoices.filter { voice ->
                val lang = voice.locale?.language?.lowercase(Locale.ROOT) ?: ""
                val features = voice.features ?: emptySet()
                val isInstalled = !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                lang == "es" && isInstalled
            }
            if (spanishVoices.isEmpty()) return false

            val preferredCountries = listOfNotNull(
                preferredLocale?.country?.uppercase(Locale.ROOT),
                "CO", "419", "MX", "US", "ES"
            )

            // Priorizar voces 100% locales (sin requerir red y sin sufijo 'network')
            val bestOfflineVoice: Voice? = spanishVoices
                .filter { !it.isNetworkConnectionRequired && !it.name.contains("network", ignoreCase = true) }
                .sortedWith(
                    compareBy<Voice> { voice ->
                        val country = voice.locale?.country?.uppercase(Locale.ROOT) ?: ""
                        val idx = preferredCountries.indexOf(country)
                        if (idx >= 0) idx else 99
                    }.thenByDescending { it.quality }
                )
                .firstOrNull()

            if (bestOfflineVoice != null) {
                tts.voice = bestOfflineVoice
                Log.d(TAG, "Voz offline seleccionada: ${bestOfflineVoice.name} (${bestOfflineVoice.locale})")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo seleccionar voz offline específica: ${e.message}")
            false
        }
    }

    private fun requestAudioFocusSafely() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                    .also { audioFocusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocusSafely() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    private fun diagnoseVolume() {
        try {
            val am = audioManager ?: return
            val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Log.d(TAG, "Nivel de volumen multimedia STREAM_MUSIC: $currentVol / $maxVol")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el nivel de volumen: ${e.message}")
        }
    }

    /**
     * Emite el tono de confirmación sonoro (Chime instantáneo).
     */
    fun playChime() {
        audioHandler.post {
            playChimeInternal()
        }
    }

    private fun playChimeInternal() {
        try {
            if (toneGenerator == null) {
                initToneGenerator()
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, CHIME_DURATION_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al reproducir chime principal: ${e.message}")
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallo al reproducir tono secundario: ${e2.message}")
            }
        }
    }

    /**
     * Encola y habla de forma fluida el anuncio de pago en una sola locución continua sin trabas.
     */
    fun speakPayment(
        amount: Long,
        includeThankYou: Boolean = true,
        senderName: String? = null,
        announceSenderName: Boolean = false
    ) {
        val rawPhrase = SpanishNumbers.buildPaymentAnnouncementPhrase(
            amount = amount,
            includeThankYou = includeThankYou,
            senderName = senderName,
            announceSenderName = announceSenderName
        )
        val fluidPhrase = formatPhraseForFluidSpeech(rawPhrase)
        Log.d(TAG, "Encolando locución fluida de pago: \"$fluidPhrase\"")
        speechQueue.offer(fluidPhrase)
        audioHandler.post { processNextInQueue() }
    }

    @Synchronized
    private fun processNextInQueue() {
        if (isSpeakingNow) {
            return
        }

        val phrase = speechQueue.poll() ?: return

        if (!isTtsInitialized || textToSpeech == null) {
            speechQueue.offer(phrase)
            return
        }

        try {
            val tts = textToSpeech
            if (tts != null) {
                requestAudioFocusSafely()
                val utteranceId = "AMB_PAY_${System.currentTimeMillis()}"
                isSpeakingNow = true
                audioHandler.removeCallbacks(watchdogRunnable)
                audioHandler.postDelayed(watchdogRunnable, UTTERANCE_WATCHDOG_TIMEOUT_MS)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val params = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }
                    val result = tts.speak(phrase, TextToSpeech.QUEUE_ADD, params, utteranceId)
                    if (result != TextToSpeech.SUCCESS) {
                        Log.e(TAG, "speak devolvió error: $result. Fallback a sonido chime.")
                        audioHandler.removeCallbacks(watchdogRunnable)
                        isSpeakingNow = false
                        abandonAudioFocusSafely()
                        playChimeInternal()
                        processNextInQueue()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val params = HashMap<String, String>().apply {
                        put(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
                        put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                    @Suppress("DEPRECATION")
                    tts.speak(phrase, TextToSpeech.QUEUE_ADD, params)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al reproducir locución: ${e.message}", e)
            audioHandler.removeCallbacks(watchdogRunnable)
            isSpeakingNow = false
            abandonAudioFocusSafely()
            playChimeInternal()
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

    /**
     * Anuncia el pago coordinando el timbre (Chime) y la voz de forma secuencial
     * para que ToneGenerator y TextToSpeech jamás choquen en el mezclador de audio del teléfono.
     */
    fun announce(
        amount: Long,
        playSound: Boolean,
        speakVoice: Boolean,
        includeThankYou: Boolean,
        senderName: String? = null,
        announceSenderName: Boolean = false
    ) {
        if (playSound && speakVoice) {
            audioHandler.post {
                playChimeInternal()
            }
            audioHandler.postDelayed({
                speakPayment(amount, includeThankYou, senderName, announceSenderName)
            }, POST_CHIME_VOICE_DELAY_MS)
        } else if (playSound) {
            playChime()
        } else if (speakVoice) {
            speakPayment(amount, includeThankYou, senderName, announceSenderName)
        }
    }

    /**
     * Emite una alerta hablada cuando la batería llega al 5% o menos sin estar conectada al cargador:
     * "Batería baja, por favor conéctame a cargar"
     */
    fun speakLowBatteryWarning() {
        val fluidPhrase = formatPhraseForFluidSpeech("Batería baja, por favor conéctame a cargar")
        audioHandler.post {
            try {
                if (toneGenerator == null) {
                    initToneGenerator()
                }
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 220)
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al reproducir tono de batería baja: ${e.message}")
            }
        }

        audioHandler.postDelayed({
            if (!speechQueue.contains(fluidPhrase)) {
                Log.d(TAG, "Encolando alerta fluida de batería crítica (<= 5%): \"$fluidPhrase\"")
                speechQueue.offer(fluidPhrase)
                processNextInQueue()
            }
        }, POST_CHIME_VOICE_DELAY_MS)
    }

    /**
     * Remueve avisos pendientes de batería baja cuando el usuario conecta el cargador.
     */
    fun cancelLowBatteryWarning() {
        val fluidPhrase = formatPhraseForFluidSpeech("Batería baja, por favor conéctame a cargar")
        speechQueue.removeAll(setOf(fluidPhrase, "Batería baja, por favor conéctame a cargar"))
    }

    /**
     * Detiene la síntesis de voz actual y limpia la cola de locuciones pendientes.
     */
    fun stop() {
        audioHandler.removeCallbacksAndMessages(null)
        try {
            speechQueue.clear()
            isSpeakingNow = false
            textToSpeech?.stop()
            abandonAudioFocusSafely()
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
        audioHandler.removeCallbacksAndMessages(null)
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {
        }

        try {
            speechQueue.clear()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isTtsInitialized = false
            _isTtsReady.value = false
            abandonAudioFocusSafely()
        } catch (_: Exception) {
        }
    }
}
