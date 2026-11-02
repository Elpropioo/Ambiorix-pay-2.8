package com.ambiorix.pay.notification

import com.ambiorix.pay.sms.NequiSmsParser
import java.util.regex.Pattern

/**
 * Parser de notificaciones de pago puramente Kotlin (sin dependencias de Android).
 * Diseñado para ser 100% testeable con JUnit.
 *
 * Soporta formatos:
 * - $35.000, $ 35.000, $35,000
 * - 35.000 pesos, 35000 pesos
 * - COP 35.000, COP $35.000
 * - $1.250.000
 * - $35.000,50 (normaliza a entero 35000)
 *
 * Ignora montos que vengan después de palabras como "saldo", "disponible", "comisión".
 * También extrae el nombre completo del remitente cuando está disponible en la notificación.
 */
class PaymentNotificationParser {

    private val amountPrefixPattern: Pattern = Pattern.compile(
        """(?i)(?:COP\s*\$|\$|COP)\s*([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+)"""
    )

    private val amountSuffixPattern: Pattern = Pattern.compile(
        """(?i)([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+)\s*(?:pesos|cop\b)"""
    )

    // Palabras que no pueden formar parte del nombre de una persona en notificaciones bancarias
    private val senderStopWordsRegex =
        """(?:el|la|los|las|un|una|al|del|de|a|en|por|con|sin|para|desde|hacia|hasta|exitosamente|hoy|ayer|saldo|disponible|comisi[oó]n|ref|referencia|cuenta|ahorros|corriente|nequi|daviplata|bancolombia|breb|bre-b|transferencia|pago|giro|abono|consignaci[oó]n|valor|pesos|cop|plata|dinero|exitoso|exitosa|aprobado|aprobada|recibido|recibida)"""

    // Patrón 1: "... de <Nombre Completo>" (después de un verbo de recepción y/o monto con puntos o comas como $25.000)
    // Nota: (?:(?![.!?](?:\s|$))[^\n])*? permite puntos y comas dentro de cifras numéricas (ej. $25.000 o $50,000.00)
    // sin cruzar el final de una oración.
    private val senderAfterDePattern: Pattern = Pattern.compile(
        """(?i)(?:recibiste|recibi[oó]|has\s+recibido|te\s+enviaron|te\s+pasaron|te\s+transfirieron|transferencia|pago|giro|abono|consignaci[oó]n)(?:(?![.!?](?:\s|$))[^\n])*?\bde\s+((?!$senderStopWordsRegex(?=\s|[.,!?;:]|$))[A-ZÁÉÍÓÚÜÑa-záéíóúüñ]+(?:\s+(?!$senderStopWordsRegex(?=\s|[.,!?;:]|$))[A-ZÁÉÍÓÚÜÑa-záéíóúüñ]+){0,5})(?=\s+(?:$senderStopWordsRegex(?=\s|[.,!?;:]|$)|\$|\d)|[.,!?;:]|$)"""
    )

    // Patrón 2: "<Nombre Completo> te transfirió / te envió / te pasó"
    private val senderBeforeVerbPattern: Pattern = Pattern.compile(
        """(?i)(?:¡?te\s+enviaron\s+plata!?\s*|¡?te\s+lleg[oó]\s+plata!?\s*|^|[.!?]\s*)((?!$senderStopWordsRegex(?=\s|[.,!?;:]|$))[A-ZÁÉÍÓÚÜÑa-záéíóúüñ]+(?:\s+(?!$senderStopWordsRegex(?=\s|[.,!?;:]|$))[A-ZÁÉÍÓÚÜÑa-záéíóúüñ]+){0,5})\s+te\s+(?:transfiri[oó]|envi[oó]|pas[oó]|pag[oó]|consign[oó])(?=\s|[.,!?;:]|$)"""
    )

    private val invalidSenderWords = setOf(
        "tu", "su", "mi", "cuenta", "nequi", "bancolombia", "daviplata",
        "plata", "dinero", "pesos", "transferencia", "pago", "saldo", "disponible", "breb",
        "ahorros", "corriente", "banco", "recepcion", "notificacion", "exito", "valor"
    )

    /**
     * Extrae el monto del título y cuerpo de la notificación.
     * Retorna el monto normalizado en centavos/enteros (Long) o null si no se detecta ninguno válido.
     */
    fun extractAmount(title: String?, text: String?): Long? {
        val fullRaw = buildString {
            if (!title.isNullOrBlank()) append(title).append(" ")
            if (!text.isNullOrBlank()) append(text)
        }
        if (fullRaw.isBlank()) return null

        val normalized = ColombianPaymentKeywords.normalizeText(fullRaw)

        // Cortar la cadena antes de palabras como "saldo", "disponible", "comision"
        // para no confundir el saldo remanente con el pago recibido.
        val effectiveText = cutBeforeBalanceWords(normalized)

        // 1. Intentar buscar en la porción antes de saldo
        var parsed = searchAmountInText(effectiveText)
        if (parsed != null && parsed > 0) {
            return parsed
        }

        // 2. Si no se encontró nada antes de saldo (o no había palabras de saldo),
        // buscar en el texto completo como fallback
        parsed = searchAmountInText(normalized)
        return if (parsed != null && parsed > 0) parsed else null
    }

    /**
     * Extrae el nombre completo del remitente cuando está disponible en la notificación.
     * Si la notificación no incluye remitente, retorna null sin inventar nombres.
     */
    fun extractSenderName(title: String?, text: String?): String? {
        val rawBody = text?.trim().orEmpty()
        if (rawBody.isEmpty()) return null

        val cleanLine = rawBody.replace("\r\n", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()

        // 1. Verificar primero si coincide con el formato estructurado de Nequi (ej. Bre-B / Negocios)
        val smsResult = NequiSmsParser.parse(null, cleanLine)
        if (smsResult is com.ambiorix.pay.sms.SmsPaymentParseResult.Success) {
            val cleanedSmsSender = sanitizeSenderCandidate(smsResult.payment.senderName)
            if (cleanedSmsSender != null) return cleanedSmsSender
        }

        // 2. Buscar patrón "... de <Nombre>" (iterando por si hay más de una coincidencia en la frase)
        val deMatcher = senderAfterDePattern.matcher(cleanLine)
        while (deMatcher.find()) {
            val candidate = sanitizeSenderCandidate(deMatcher.group(1))
            if (candidate != null) return candidate
        }

        // 3. Buscar patrón "<Nombre> te transfirió / te envió"
        val beforeMatcher = senderBeforeVerbPattern.matcher(cleanLine)
        while (beforeMatcher.find()) {
            val candidate = sanitizeSenderCandidate(beforeMatcher.group(1))
            if (candidate != null) return candidate
        }

        return null
    }

    private fun sanitizeSenderCandidate(raw: String?): String? {
        val cleaned = NequiSmsParser.cleanSenderName(raw) ?: return null
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 6) return null

        val normalizedWords = words.map { ColombianPaymentKeywords.normalizeText(it) }
        if (normalizedWords.any { it in invalidSenderWords }) return null

        val normalizedJoin = words.joinToString(" ")
        if (!NequiSmsParser.isValidPersonName(normalizedJoin)) return null
        return normalizedJoin
    }

    private fun cutBeforeBalanceWords(text: String): String {
        var earliestIndex = -1
        for (keyword in ColombianPaymentKeywords.BALANCE_KEYWORDS) {
            val idx = text.indexOf(keyword)
            if (idx >= 0) {
                if (earliestIndex == -1 || idx < earliestIndex) {
                    earliestIndex = idx
                }
            }
        }
        return if (earliestIndex >= 0) {
            text.substring(0, earliestIndex)
        } else {
            text
        }
    }

    private fun searchAmountInText(text: String): Long? {
        // Primero buscar con prefijo ($ o COP)
        val prefixMatcher = amountPrefixPattern.matcher(text)
        if (prefixMatcher.find()) {
            val rawNumber = prefixMatcher.group(1)
            val parsed = normalizeRawNumber(rawNumber)
            if (parsed != null && parsed > 0) return parsed
        }

        // Segundo buscar con sufijo (pesos o cop)
        val suffixMatcher = amountSuffixPattern.matcher(text)
        if (suffixMatcher.find()) {
            val rawNumber = suffixMatcher.group(1)
            val parsed = normalizeRawNumber(rawNumber)
            if (parsed != null && parsed > 0) return parsed
        }

        return null
    }

    /**
     * Normaliza representaciones numéricas como "35.000", "35,000", "1.250.000", "35.000,50"
     * a entero Long (35000).
     */
    fun normalizeRawNumber(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        var str = raw.trim()

        // Si tiene decimales al final: coma o punto seguido de 1 o 2 dígitos (ej: ,50 o .5 o ,00)
        // OJO: Si son 3 dígitos precedidos de punto o coma (ej: .000 o ,000), son miles, no decimales.
        val decimalMatch = """[.,](\d{1,2})$""".toRegex().find(str)
        if (decimalMatch != null) {
            str = str.substring(0, decimalMatch.range.first)
        }

        // Remover todos los separadores de miles restantes (puntos, comas o espacios)
        val cleanedDigits = str.replace("""[.,\s]""".toRegex(), "")
        if (cleanedDigits.isBlank()) return null

        return try {
            cleanedDigits.toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }
}
