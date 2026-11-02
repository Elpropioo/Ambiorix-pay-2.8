package com.ambiorix.pay.notification

import android.content.Context
import com.ambiorix.pay.data.Payment
import com.ambiorix.pay.data.SettingsRepository
import com.ambiorix.pay.sound.SoundAnnouncer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

sealed class UiPaymentEvent {
    data class RealPayment(val payment: Payment) : UiPaymentEvent()
    data class SimulatedTestPayment(val payment: Payment) : UiPaymentEvent()
}

/**
 * Canal en memoria con buffer garantizado entre el servicio NotificationListenerService /
 * AmbiorixSmsReceiver y las actividades de la interfaz de usuario.
 */
object NotificationPaymentEvents {

    private val _paymentChannel = Channel<UiPaymentEvent>(capacity = Channel.UNLIMITED)
    val paymentEvents: Flow<UiPaymentEvent> = _paymentChannel.receiveAsFlow()

    private val _isListenerConnected = MutableStateFlow(false)
    val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()

    private val _isMainActivityActive = MutableStateFlow(false)
    val isMainActivityActive: StateFlow<Boolean> = _isMainActivityActive.asStateFlow()

    fun emitRealPayment(payment: Payment, appContext: Context? = null) {
        if (!_isMainActivityActive.value && appContext != null) {
            val settings = SettingsRepository.getInstance(appContext).getSettings()
            if (settings.isServiceEnabled && settings.businessHours.isWithinBusinessHours()) {
                SoundAnnouncer.getInstance(appContext).announce(
                    amount = payment.amount,
                    playSound = settings.soundEnabled,
                    speakVoice = settings.ttsEnabled,
                    includeThankYou = settings.includeThankYou,
                    senderName = payment.senderName,
                    announceSenderName = settings.announceSenderName
                )
            }
        }
        _paymentChannel.trySend(UiPaymentEvent.RealPayment(payment))
    }

    fun emitTestPayment(testAmount: Long = 35000L) {
        val testPayment = Payment(
            amount = testAmount,
            appName = "Prueba de Sistema",
            packageName = "com.ambiorix.pay.test",
            senderName = "Cliente de Prueba",
            timestampMillis = System.currentTimeMillis()
        )
        _paymentChannel.trySend(UiPaymentEvent.SimulatedTestPayment(testPayment))
    }

    fun setListenerConnected(connected: Boolean) {
        _isListenerConnected.value = connected
    }

    fun setMainActivityActive(active: Boolean) {
        _isMainActivityActive.value = active
    }
}
