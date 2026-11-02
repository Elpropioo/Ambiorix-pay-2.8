package com.ambiorix.pay

import com.ambiorix.pay.notification.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PaymentNotificationFilterTest {

    private var simulatedTime: Long = 1000000000L
    private lateinit var filter: PaymentNotificationFilter
    private val authorizedPackages = setOf("com.nequi.MobileApp", "com.bancolombia.personas.gemini")

    @Before
    fun setup() {
        simulatedTime = 1000000000L
        filter = PaymentNotificationFilter(
            parser = PaymentNotificationParser(),
            clock = { simulatedTime }
        )
    }

    @Test
    fun testAcceptsValidIncomingPayment() {
        val result = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "¡Te enviaron plata! Carlos te transfirió $35.000.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(result is FilterResult.Accepted)
        assertEquals(35000L, (result as FilterResult.Accepted).amount)
    }

    @Test
    fun testRejectsUnauthorizedApp() {
        val result = filter.evaluate(
            packageName = "com.unauthorized.app",
            title = "Alerta",
            text = "Recibiste $35.000",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(result is FilterResult.Rejected)
        assertEquals(RejectionReason.UNAUTHORIZED_APP, (result as FilterResult.Rejected).reason)
    }

    @Test
    fun testRejectsOutgoingTransactionKeyword() {
        // Notificación con palabra de exclusión "enviaste"
        val result = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "Enviaste $35.000 a Juan exitosamente.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(result is FilterResult.Rejected)
        assertEquals(RejectionReason.EXCLUSION_KEYWORD_FOUND, (result as FilterResult.Rejected).reason)
    }

    @Test
    fun testRejectsSecurityCodeOrOtp() {
        val result = filter.evaluate(
            packageName = "com.bancolombia.personas.gemini",
            title = "Bancolombia",
            text = "Tu código de seguridad para pago es 482910.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(result is FilterResult.Rejected)
        assertEquals(RejectionReason.EXCLUSION_KEYWORD_FOUND, (result as FilterResult.Rejected).reason)
    }

    @Test
    fun testRejectsWhenNoIncomingKeywordPresent() {
        val result = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "Tu suscripción mensual es de $15.000.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(result is FilterResult.Rejected)
        assertEquals(RejectionReason.NO_INCOMING_KEYWORD_FOUND, (result as FilterResult.Rejected).reason)
    }

    @Test
    fun testRejectsDuplicatePaymentWithinTwoMinutes() {
        // Primer pago
        val first = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "Recibiste $40.000 de Pedro.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(first is FilterResult.Accepted)

        // Mismo monto, misma app, 30 segundos después (dentro de la ventana de 2 minutos)
        simulatedTime += 30 * 1000L
        val duplicate = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "Recibiste $40.000 de Pedro.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(duplicate is FilterResult.Rejected)
        assertEquals(RejectionReason.DUPLICATE_WITHIN_WINDOW, (duplicate as FilterResult.Rejected).reason)

        // 2 minutos y 1 segundo después (121 segundos): ya no debe considerarse duplicado
        simulatedTime += 91 * 1000L
        val nextAllowed = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "Recibiste $40.000 de Pedro.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(nextAllowed is FilterResult.Accepted)
    }

    @Test
    fun testRejectsNotificationOlderThanThreeMinutes() {
        // Notificación de hace 5 minutos (300 segundos)
        val oldPostTime = simulatedTime - (300 * 1000L)
        val result = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "Recibiste $35.000.",
            notificationPostTimeMillis = oldPostTime,
            authorizedPackages = authorizedPackages
        )
        assertTrue(result is FilterResult.Rejected)
        assertEquals(RejectionReason.NOTIFICATION_TOO_OLD, (result as FilterResult.Rejected).reason)
    }

    @Test
    fun testRejectsWhenServiceDisabled() {
        val result = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "Recibiste $35.000.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages,
            isServiceEnabled = false,
            isWithinBusinessHours = true
        )
        assertTrue(result is FilterResult.Rejected)
        assertEquals(RejectionReason.SERVICE_DISABLED, (result as FilterResult.Rejected).reason)
    }

    @Test
    fun testRejectsWhenOutsideBusinessHours() {
        val result = filter.evaluate(
            packageName = "com.nequi.MobileApp",
            title = "Nequi",
            text = "Recibiste $35.000.",
            notificationPostTimeMillis = simulatedTime,
            authorizedPackages = authorizedPackages,
            isServiceEnabled = true,
            isWithinBusinessHours = false
        )
        assertTrue(result is FilterResult.Rejected)
        assertEquals(RejectionReason.OUTSIDE_BUSINESS_HOURS, (result as FilterResult.Rejected).reason)
    }
}
