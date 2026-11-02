package com.ambiorix.pay.sound

import java.util.Locale

/**
 * Conversor numérico a palabras en español con gramática, ortografía y fonética precisa:
 * - 35000 -> "treinta y cinco mil pesos"
 * - 21000 -> "veintiún mil pesos"
 * - 100000 -> "cien mil pesos"
 * - 1250 -> "mil doscientos cincuenta pesos"
 * - 1000000 -> "un millón de pesos"
 * - 1500000 -> "un millón quinientos mil pesos"
 * - 1 -> "un peso"
 *
 * Construye una única frase natural sin dígitos ni símbolos numéricos,
 * adaptada opcionalmente para anunciar el nombre de la persona que transfirió:
 * "Pago recibido de Santiago Morgan, por un peso. Gracias por tu compra."
 * o sin nombre:
 * "Pago recibido, treinta y cinco mil pesos. Gracias por tu compra."
 */
object SpanishNumbers {

    private val UNITS = arrayOf(
        "", "un", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
        "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete",
        "dieciocho", "diecinueve", "veinte", "veintiún", "veintidós", "veintitrés",
        "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"
    )

    private val TENS = arrayOf(
        "", "", "", "treinta", "cuarenta", "cincuenta",
        "sesenta", "setenta", "ochenta", "noventa"
    )

    private val HUNDREDS = arrayOf(
        "", "ciento", "doscientos", "trescientos", "cuatrocientos",
        "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos"
    )

    /**
     * Convierte un número menor a 1000 en palabras en español.
     */
    fun convertGroupUnderThousand(n: Int): String {
        if (n == 0) return ""
        if (n == 100) return "cien"

        val sb = StringBuilder()

        val h = n / 100
        val rem100 = n % 100

        if (h > 0) {
            sb.append(HUNDREDS[h])
        }

        if (rem100 > 0) {
            if (sb.isNotEmpty()) sb.append(" ")

            if (rem100 < 30) {
                sb.append(UNITS[rem100])
            } else {
                val t = rem100 / 10
                val u = rem100 % 10
                sb.append(TENS[t])
                if (u > 0) {
                    sb.append(" y ")
                    sb.append(UNITS[u])
                }
            }
        }

        return sb.toString().trim()
    }

    /**
     * Convierte cualquier monto entero no negativo en su expresión en palabras puras
     * (sin dígitos, sin puntos de miles).
     */
    fun toWords(amount: Long): String {
        if (amount == 0L) return "cero"
        if (amount < 0) return "menos " + toWords(-amount)

        val millions = (amount / 1_000_000).toInt()
        val thousands = ((amount % 1_000_000) / 1_000).toInt()
        val units = (amount % 1_000).toInt()

        val parts = mutableListOf<String>()

        // Millones
        if (millions > 0) {
            if (millions == 1) {
                parts.add("un millón")
            } else {
                parts.add(convertGroupUnderThousand(millions) + " millones")
            }
        }

        // Miles
        if (thousands > 0) {
            if (thousands == 1) {
                parts.add("mil")
            } else {
                parts.add(convertGroupUnderThousand(thousands) + " mil")
            }
        }

        // Unidades
        if (units > 0) {
            parts.add(convertGroupUnderThousand(units))
        }

        return parts.joinToString(" ").trim()
    }

    private fun capitalizeWord(word: String): String {
        if (word.isBlank()) return ""
        val lower = word.lowercase(Locale.getDefault())
        return lower.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    /**
     * Extrae de forma inteligente y natural el primer nombre y el primer apellido de una persona.
     * Ejemplo:
     * - "SANTIAGO ALEJANDRO MORGAN AVILA" -> "Santiago Morgan"
     * - "SANTIAGO MORGAN AVILA" -> "Santiago Morgan"
     * - "SANTIAGO MORGAN" -> "Santiago Morgan"
     * - "SANTIAGO" -> "Santiago"
     */
    fun extractShortNameForVoice(fullName: String?): String? {
        if (fullName.isNullOrBlank()) return null
        // Limpiar caracteres especiales conservando letras latinas y espacios
        val cleaned = fullName.trim().replace(Regex("[^A-ZÁÉÍÓÚÑa-záéíóúñ\\s]"), "")
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        return when (words.size) {
            1 -> capitalizeWord(words[0])
            2 -> "${capitalizeWord(words[0])} ${capitalizeWord(words[1])}"
            3 -> "${capitalizeWord(words[0])} ${capitalizeWord(words[1])}"
            4 -> "${capitalizeWord(words[0])} ${capitalizeWord(words[2])}"
            else -> {
                // 5 o más palabras: ej. María del Carmen Gómez Pérez
                val firstName = capitalizeWord(words[0])
                val surnameCandidate = if (words[2].equals("DE", ignoreCase = true) || words[2].equals("DEL", ignoreCase = true)) {
                    if (words.size > 3) "${capitalizeWord(words[2])} ${capitalizeWord(words[3])}" else capitalizeWord(words[2])
                } else {
                    capitalizeWord(words[2])
                }
                "$firstName $surnameCandidate"
            }
        }
    }

    /**
     * Construye la frase de locución completa para TextToSpeech.
     * Soporta:
     * - Anuncio solo de monto: "Pago recibido, un peso."
     * - Anuncio con nombre: "Pago recibido de Santiago Morgan, por un peso."
     * - Inclusión opcional de "Gracias por tu compra."
     */
    fun buildPaymentAnnouncementPhrase(
        amount: Long,
        includeThankYou: Boolean = true,
        senderName: String? = null,
        announceSenderName: Boolean = false
    ): String {
        val words = toWords(amount)
        val amountWithCurrency = if (amount == 1L) {
            "un peso"
        } else if (amount % 1_000_000 == 0L && amount > 0L) {
            "$words de pesos"
        } else {
            "$words pesos"
        }

        val shortName = if (announceSenderName && !senderName.isNullOrBlank()) {
            extractShortNameForVoice(senderName)
        } else {
            null
        }

        val basePhrase = if (shortName != null) {
            "Pago recibido de $shortName, por $amountWithCurrency"
        } else {
            "Pago recibido, $amountWithCurrency"
        }

        return if (includeThankYou) {
            "$basePhrase. Gracias por tu compra."
        } else {
            "$basePhrase."
        }
    }

    /**
     * Sobrecarga retrocompatible.
     */
    fun buildPaymentAnnouncementPhrase(amount: Long, includeThankYou: Boolean): String {
        return buildPaymentAnnouncementPhrase(
            amount = amount,
            includeThankYou = includeThankYou,
            senderName = null,
            announceSenderName = false
        )
    }

    fun toPaymentAnnouncementPhrase(amount: Long): String {
        return buildPaymentAnnouncementPhrase(amount, includeThankYou = false)
    }
}
