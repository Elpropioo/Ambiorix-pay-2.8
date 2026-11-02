package com.ambiorix.pay

import com.ambiorix.pay.notification.PaymentNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PaymentNotificationParserTest {

    private lateinit var parser: PaymentNotificationParser

    @Before
    fun setup() {
        parser = PaymentNotificationParser()
    }

    @Test
    fun testAmountWithDollarNoSpace() {
        val amount = parser.extractAmount("Nequi", "¡Te llegó plata! Te enviaron $35.000 a tu cuenta.")
        assertEquals(35000L, amount)
    }

    @Test
    fun testAmountWithDollarAndSpace() {
        val amount = parser.extractAmount("Daviplata", "Recibiste $ 35.000 exitosamente.")
        assertEquals(35000L, amount)
    }

    @Test
    fun testAmountWithCommaSeparator() {
        val amount = parser.extractAmount("Bancolombia", "Transferencia recibida por $35,000 en su cuenta.")
        assertEquals(35000L, amount)
    }

    @Test
    fun testAmountWithPesosSuffixDot() {
        val amount = parser.extractAmount("Nequi", "Te transfirieron 35.000 pesos.")
        assertEquals(35000L, amount)
    }

    @Test
    fun testAmountWithPesosSuffixClean() {
        val amount = parser.extractAmount("A la mano", "Has recibido 35000 pesos de Juan.")
        assertEquals(35000L, amount)
    }

    @Test
    fun testAmountWithCopPrefix() {
        val amount = parser.extractAmount("Transferencia", "Recibiste COP 35.000 de Carlos.")
        assertEquals(35000L, amount)
    }

    @Test
    fun testAmountWithCopAndDollar() {
        val amount = parser.extractAmount("Pago", "Entrada de dinero por COP $35.000.")
        assertEquals(35000L, amount)
    }

    @Test
    fun testAmountOverOneMillion() {
        val amount = parser.extractAmount("Bancolombia", "Has recibido $1.250.000 de Pedro Pérez.")
        assertEquals(1250000L, amount)
    }

    @Test
    fun testAmountWithCentavosDecimalsNormalizedToEntero() {
        val amount = parser.extractAmount("Bancolombia", "Recibiste $35.000,50 de María.")
        assertEquals(35000L, amount)
    }

    @Test
    fun testIgnoresBalanceAmountAfterReceivedAmount() {
        val text = "¡Te enviaron plata! Carlos te transfirió $35.000 a tu Nequi. Tu nuevo saldo disponible es $180.000."
        val amount = parser.extractAmount("Nequi", text)
        // Debe extraer el pago de 35000, ignorando completamente el saldo de 180000
        assertEquals(35000L, amount)
    }

    @Test
    fun testIgnoresCommissionAmount() {
        val text = "Recibiste $50.000 en tu cuenta. Comisión del servicio: $2.500."
        val amount = parser.extractAmount("Bancolombia", text)
        assertEquals(50000L, amount)
    }

    @Test
    fun testInvalidNotificationYieldsNull() {
        val amount = parser.extractAmount("Nequi", "Bienvenido a Nequi. Disfruta tu día.")
        assertNull(amount)
    }

    @Test
    fun testExtractsSenderNameFromNequiAndBankNotifications() {
        val sender1 = parser.extractSenderName(
            "Nequi",
            "¡Te enviaron plata! Carlos Rodríguez te transfirió $12.000 a tu cuenta. Saldo $140.000"
        )
        assertEquals("Carlos Rodríguez", sender1)

        val sender2 = parser.extractSenderName(
            "Daviplata",
            "Has recibido $25.000 de María Pérez."
        )
        assertEquals("María Pérez", sender2)

        val sender3 = parser.extractSenderName(
            "Nequi",
            "Notificación Nequi: el negocio (TECNICABLES) recibió $45.000 de LAURA GÓMEZ el 30/10/2026 a las 10:03 a.m. a través de Bre-B."
        )
        assertEquals("LAURA GÓMEZ", sender3)

        val sender4 = parser.extractSenderName(
            "Bancolombia",
            "Bancolombia le informa recepción de transferencia de Andrés Felipe Morales por $50,000.00 en su cuenta de ahorros."
        )
        assertEquals("Andrés Felipe Morales", sender4)

        val sender5 = parser.extractSenderName(
            "Nequi",
            "Recibiste $1.250.000 de Juan Carlos Gómez exitosamente."
        )
        assertEquals("Juan Carlos Gómez", sender5)

        val sender6 = parser.extractSenderName(
            "Nequi",
            "  Recibiste   $85.000   de   María   José   Rodríguez   Peña   exitosamente.  "
        )
        assertEquals("María José Rodríguez Peña", sender6)

        val sender7 = parser.extractSenderName(
            "Nequi",
            "Te enviaron $32.000 de Óscar Iván Núñez en tu Nequi."
        )
        assertEquals("Óscar Iván Núñez", sender7)

        val noSender = parser.extractSenderName(
            "Bancolombia",
            "Transferencia recibida por $50.000 en su cuenta."
        )
        assertNull(noSender)
    }

    @Test
    fun testNequiSmsParserFormats() {
        val res1 = com.ambiorix.pay.sms.NequiSmsParser.parse(
            "890806",
            "Notificación Nequi: el negocio (TECNICABLES, nombre ficticio) recibió $45.000 de LAURA GÓMEZ el 30/10/2026 a las 10:03 a.m. a través de Bre-B."
        )
        org.junit.Assert.assertTrue(res1 is com.ambiorix.pay.sms.SmsPaymentParseResult.Success)
        val p1 = (res1 as com.ambiorix.pay.sms.SmsPaymentParseResult.Success).payment
        assertEquals(45000L, p1.amount)
        assertEquals("LAURA GÓMEZ", p1.senderName)

        val res2 = com.ambiorix.pay.sms.NequiSmsParser.parse(
            "890806",
            "Nequi: ¡Te llegó plata! Recibiste $35.000 de Carlos Andrés Pérez en tu Nequi. Tu nuevo saldo disponible es $180.000."
        )
        org.junit.Assert.assertTrue(res2 is com.ambiorix.pay.sms.SmsPaymentParseResult.Success)
        val p2 = (res2 as com.ambiorix.pay.sms.SmsPaymentParseResult.Success).payment
        assertEquals(35000L, p2.amount)
        assertEquals("Carlos Andrés Pérez", p2.senderName)

        val res3 = com.ambiorix.pay.sms.NequiSmsParser.parse(
            "85954",
            "¡Te enviaron plata! Carlos Rodríguez te transfirió $12.000 a tu cuenta. Saldo $140.000"
        )
        org.junit.Assert.assertTrue(res3 is com.ambiorix.pay.sms.SmsPaymentParseResult.Success)
        val p3 = (res3 as com.ambiorix.pay.sms.SmsPaymentParseResult.Success).payment
        assertEquals(12000L, p3.amount)
        assertEquals("Carlos Rodríguez", p3.senderName)
    }
}
