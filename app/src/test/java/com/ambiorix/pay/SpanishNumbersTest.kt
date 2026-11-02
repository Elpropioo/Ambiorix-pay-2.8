package com.ambiorix.pay

import com.ambiorix.pay.sound.SpanishNumbers
import org.junit.Assert.assertEquals
import org.junit.Test

class SpanishNumbersTest {

    @Test
    fun testThirtyFiveThousand() {
        assertEquals("treinta y cinco mil", SpanishNumbers.toWords(35000L))

        // Con "Gracias por tu compra"
        assertEquals(
            "Pago recibido, treinta y cinco mil pesos. Gracias por tu compra.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(35000L, includeThankYou = true)
        )

        // Sin "Gracias por tu compra"
        assertEquals(
            "Pago recibido, treinta y cinco mil pesos.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(35000L, includeThankYou = false)
        )
    }

    @Test
    fun testTwentyOneThousand() {
        assertEquals("veintiún mil", SpanishNumbers.toWords(21000L))

        assertEquals(
            "Pago recibido, veintiún mil pesos. Gracias por tu compra.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(21000L, includeThankYou = true)
        )
        assertEquals(
            "Pago recibido, veintiún mil pesos.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(21000L, includeThankYou = false)
        )
    }

    @Test
    fun testOneHundredThousand() {
        assertEquals("cien mil", SpanishNumbers.toWords(100000L))

        assertEquals(
            "Pago recibido, cien mil pesos. Gracias por tu compra.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(100000L, includeThankYou = true)
        )
        assertEquals(
            "Pago recibido, cien mil pesos.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(100000L, includeThankYou = false)
        )
    }

    @Test
    fun testOneThousandTwoHundredFifty() {
        assertEquals("mil doscientos cincuenta", SpanishNumbers.toWords(1250L))

        assertEquals(
            "Pago recibido, mil doscientos cincuenta pesos. Gracias por tu compra.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(1250L, includeThankYou = true)
        )
        assertEquals(
            "Pago recibido, mil doscientos cincuenta pesos.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(1250L, includeThankYou = false)
        )
    }

    @Test
    fun testOneMillionExact() {
        assertEquals("un millón", SpanishNumbers.toWords(1000000L))

        // Exacto millón usa "de pesos"
        assertEquals(
            "Pago recibido, un millón de pesos. Gracias por tu compra.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(1000000L, includeThankYou = true)
        )
        assertEquals(
            "Pago recibido, un millón de pesos.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(1000000L, includeThankYou = false)
        )
    }

    @Test
    fun testOneMillionFiveHundredThousand() {
        assertEquals("un millón quinientos mil", SpanishNumbers.toWords(1500000L))

        // Al terminar en mil, usa "pesos" directamente
        assertEquals(
            "Pago recibido, un millón quinientos mil pesos. Gracias por tu compra.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(1500000L, includeThankYou = true)
        )
        assertEquals(
            "Pago recibido, un millón quinientos mil pesos.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(1500000L, includeThankYou = false)
        )
    }

    @Test
    fun testSpecificSmallAndTensAmounts() {
        assertEquals("diez mil", SpanishNumbers.toWords(10000L))
        assertEquals("cincuenta mil", SpanishNumbers.toWords(50000L))
        assertEquals("quinientos mil", SpanishNumbers.toWords(500000L))

        assertEquals(
            "Pago recibido, diez mil pesos. Gracias por tu compra.",
            SpanishNumbers.buildPaymentAnnouncementPhrase(10000L, includeThankYou = true)
        )
    }
}
