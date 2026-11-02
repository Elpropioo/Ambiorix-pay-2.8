package com.ambiorix.pay.sms

import android.util.Log

/**
 * Datos extraídos de una notificación SMS de pago de Nequi.
 */
data class ParsedSmsPayment(
    val amount: Long,
    val senderName: String,
    val receiverBusiness: String?,
    val transactionDateTimeText: String?,
    val channel: String?,
    val originatingAddress: String?,
    val rawBody: String
)

/**
 * Resultado estructurado del análisis de SMS para diagnósticos precisos.
 */
sealed class SmsPaymentParseResult {
    data class Success(val payment: ParsedSmsPayment) : SmsPaymentParseResult()
    data class Ignored(val reason: String) : SmsPaymentParseResult()
}

/**
 * Analizador modular de mensajes SMS de transacciones bancarias de Nequi.
 * 
 * Reglas de seguridad:
 * 1. Reconoce la estructura formal de notificación de pago de Nequi.
 * 2. Reconoce el remitente conocido '890806', pero permite otros remitentes siempre que la estructura
 *    del cuerpo sea un pago legítimo verificado.
 * 3. Descarta códigos OTP, solicitudes de préstamo, promociones, alertas de seguridad o chats normales.
 * 4. Tolera mayúsculas/minúsculas, acentos, saltos de línea y espacios múltiples.
 * 5. Separa estrictamente el nombre del negocio receptor del nombre de la persona que transfirió.
 */
object NequiSmsParser {

    private const val TAG = "NequiSmsParser"

    // Remitente SMS oficial conocido de Nequi Colombia
    const val KNOWN_NEQUI_SENDER = "890806"

    // Palabras prohibidas que identifican OTPs, seguridad o alertas de acceso
    private val SECURITY_AND_OTP_KEYWORDS = listOf(
        "clave dinamica", "clave dinámica", "codigo de seguridad", "código de seguridad",
        "codigo de verificacion", "código de verificación", "tu codigo es", "tu código es",
        "tu clave es", "otp", "no compartas este codigo", "no compartas este código",
        "inicio de sesion", "inicio de sesión", "cambio de clave", "bloqueo de cuenta"
    )

    // Palabras prohibidas que identifican publicidad, préstamos o promociones
    private val ADVERTISING_KEYWORDS = listOf(
        "credito salvavidas", "crédito salvavidas", "pide tu credito", "pide tu crédito",
        "prestamo propulsor", "préstamo propulsor", "te prestamos", "te prestamos hasta",
        "aprovecha hoy", "tarjeta de credito", "tarjeta de crédito", "descuento",
        "gana", "sorteo", "promocion", "promoción", "entra ya a", "participa"
    )

    // Palabras que nunca forman parte del nombre real de un remitente
    private val NON_PERSON_WORDS = setOf(
        "exitosamente", "exito", "éxito", "exitoso", "exitosa",
        "cuenta", "nequi", "negocios", "daviplata", "bancolombia", "breb",
        "saldo", "disponible", "transferencia", "pago", "pesos", "cop", "plata", "dinero",
        "tu", "su", "mi", "el", "la", "en", "por", "con", "para", "hoy", "ayer"
    )

    /**
     * Patrón primario:
     * "Notificacion Nequi: el negocio (TECNICABLES, nombre ficticio) recibió $1 de SANTIAGO ALEJANDRO MORGAN AVILA el 19/10/2026 a las 07:11 p.m. a través de Bre-B."
     */
    private val NEQUI_PRIMARY_PATTERN = Regex(
        "(?i)^\\s*(?:Notificaci[oó]n\\s+Nequi\\s*:\\s*)?" +
                "el\\s+negocio\\s*(?:\\(([^)]+)\\)|([A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ\\s.,-]+?))\\s+" +
                "recibi[oó]\\s+\\$\\s*([0-9.,]+)\\s+de\\s+" +
                "([A-Za-zÁÉÍÓÚÜÑáéíóúüñ\\s]+?)" +
                "(?:\\s+(?:exitosamente|con\\s+[eé]xito|exitoso|exitosa))?" +
                "(?:\\s+(?:en|a)\\s+tu\\s+(?:cuenta(?:\\s+Nequi)?|Nequi(?:\\s+Negocios)?))?" +
                "(?:\\s+el\\s+([0-9]{1,2}/[0-9]{1,2}/[0-9]{2,4}(?:\\s+a\\s+las\\s+[0-9]{1,2}:[0-9]{2}(?:\\s*[a-z. ]+)?)?))?" +
                "(?:\\s+a\\s+trav[eé]s\\s+de\\s+([A-Za-z0-9\\-_ ]+?))?" +
                "(?:\\s+(?:exitosamente|con\\s+[eé]xito|exitoso|exitosa))?" +
                "(?:\\.?\\s*(?:Ref|Referencia)\\.?\\s*:?\\s*[A-Za-z0-9\\-]+)?\\.?\\s*$"
    )

    /**
     * Patrón secundario para variantes directas de Nequi:
     * ej: "Nequi: Recibiste $35.000 de JUAN PEREZ el 19/10/2026 a las 03:45 p.m."
     * o "Recibiste $1.250.000 de Juan Carlos Gómez exitosamente."
     * o "¡Te llegó plata! Recibiste $25.000 de María López en tu Nequi. Tu nuevo saldo..."
     */
    private val NEQUI_SECONDARY_PATTERN = Regex(
        "(?i)^\\s*(?:Notificaci[oó]n\\s+Nequi\\s*:\\s*|Nequi\\s*:\\s*)?" +
                "(?:¡?te\\s+lleg[oó]\\s+plata!?\\s*:?\\s*|¡?te\\s+enviaron\\s+plata!?\\s*:?\\s*)?" +
                "(?:recibiste|recibi[oó]|has\\s+recibido|te\\s+enviaron|te\\s+pasaron|te\\s+transfirieron)" +
                "(?:\\s+(?:un\\s+pago|una\\s+transferencia|plata|dinero)\\s+(?:por|de))?\\s+" +
                "(?:COP\\s*)?\\$\\s*([0-9.,]+)\\s+de\\s+" +
                "([A-Za-zÁÉÍÓÚÜÑáéíóúüñ\\s]+?)" +
                "(?:\\s+(?:exitosamente|con\\s+[eé]xito|exitoso|exitosa))?" +
                "(?:\\s+(?:en|a)\\s+(?:tu|su)\\s+(?:cuenta(?:\\s+Nequi)?|Nequi(?:\\s+Negocios)?))?" +
                "(?:\\s+el\\s+([0-9]{1,2}/[0-9]{1,2}/[0-9]{2,4}(?:\\s+a\\s+las\\s+[0-9]{1,2}:[0-9]{2}(?:\\s*[a-z. ]+)?)?))?" +
                "(?:\\s+a\\s+trav[eé]s\\s+de\\s+([A-Za-z0-9\\-_ ]+?))?" +
                "(?:\\s+(?:exitosamente|con\\s+[eé]xito|exitoso|exitosa))?" +
                "(?:[.,;]?\\s*(?:(?:Ref|Referencia|Saldo|Tu\\s+nuevo\\s+saldo|Conoce)\\b.*)?)?\\s*$"
    )

    /**
     * Patrón terciario cuando el nombre del remitente aparece antes del verbo:
     * ej: "¡Te enviaron plata! Carlos Rodríguez te transfirió $12.000 a tu Nequi."
     */
    private val NEQUI_SENDER_FIRST_PATTERN = Regex(
        "(?i)^\\s*(?:Notificaci[oó]n\\s+Nequi\\s*:\\s*|Nequi\\s*:\\s*)?" +
                "(?:¡?te\\s+lleg[oó]\\s+plata!?\\s*:?\\s*|¡?te\\s+enviaron\\s+plata!?\\s*:?\\s*)?" +
                "([A-Za-zÁÉÍÓÚÜÑáéíóúüñ\\s]+?)\\s+te\\s+(?:transfiri[oó]|envi[oó]|pas[oó]|pag[oó]|consign[oó])\\s+" +
                "(?:COP\\s*)?\\$\\s*([0-9.,]+)" +
                "(?:\\s+(?:a|en)\\s+(?:tu|su)\\s+(?:cuenta(?:\\s+Nequi)?|Nequi(?:\\s+Negocios)?))?" +
                "(?:[.,;]?\\s*.*)?$"
    )


    fun parse(originatingAddress: String?, body: String?): SmsPaymentParseResult {
        if (body.isNullOrBlank()) {
            return SmsPaymentParseResult.Ignored("Cuerpo de mensaje SMS vacío o nulo")
        }

        val normalized = body.replace("\r\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val lower = normalized.lowercase()

        // 1. Filtrar inmediatamente mensajes de autenticación, OTP o seguridad
        for (kw in SECURITY_AND_OTP_KEYWORDS) {
            if (lower.contains(kw)) {
                return SmsPaymentParseResult.Ignored("Mensaje descartado por contener clave dinámica/código de seguridad: '$kw'")
            }
        }

        // 2. Filtrar mensajes publicitarios o promocionales
        for (kw in ADVERTISING_KEYWORDS) {
            if (lower.contains(kw)) {
                return SmsPaymentParseResult.Ignored("Mensaje descartado por contener publicidad o préstamos: '$kw'")
            }
        }

        // 3. Verificar que contenga al menos un indicador monetario de recepción
        if (!lower.contains("$") && !lower.contains("recibi") && !lower.contains("transfiri") && !lower.contains("enviar")) {
            return SmsPaymentParseResult.Ignored("Mensaje no contiene indicador de dinero recibido ni símbolo '$'")
        }

        // 4. Evaluar con patrón primario
        val primaryMatch = NEQUI_PRIMARY_PATTERN.matchEntire(normalized)
        if (primaryMatch != null) {
            val rawBiz = primaryMatch.groups[1]?.value ?: primaryMatch.groups[2]?.value
            val cleanBiz = cleanBusinessName(rawBiz)
            val rawAmount = primaryMatch.groups[3]?.value
            val rawSenderName = cleanSenderName(primaryMatch.groups[4]?.value)
            val rawDateTime = primaryMatch.groups[5]?.value?.trim()
            val rawChannel = primaryMatch.groups[6]?.value?.trim()

            val amount = parseColombianAmount(rawAmount)
            if (amount == null || amount <= 0L) {
                return SmsPaymentParseResult.Ignored("No se pudo extraer un monto monetario válido: '$rawAmount'")
            }

            if (!isValidPersonName(rawSenderName)) {
                return SmsPaymentParseResult.Ignored("El nombre del remitente no es válido: '$rawSenderName'")
            }

            // Regla de seguridad: el remitente no puede ser el mismo negocio receptor
            if (cleanBiz != null && cleanBiz.equals(rawSenderName, ignoreCase = true)) {
                return SmsPaymentParseResult.Ignored("El remitente coincide con el negocio receptor")
            }

            return SmsPaymentParseResult.Success(
                ParsedSmsPayment(
                    amount = amount,
                    senderName = rawSenderName!!,
                    receiverBusiness = cleanBiz,
                    transactionDateTimeText = rawDateTime,
                    channel = rawChannel ?: "Bre-B",
                    originatingAddress = originatingAddress,
                    rawBody = body
                )
            )
        }

        // 5. Evaluar con patrón secundario alternativo
        val secondaryMatch = NEQUI_SECONDARY_PATTERN.matchEntire(normalized)
        if (secondaryMatch != null) {
            val rawAmount = secondaryMatch.groups[1]?.value
            val rawSenderName = cleanSenderName(secondaryMatch.groups[2]?.value)
            val rawDateTime = secondaryMatch.groups[3]?.value?.trim()
            val rawChannel = secondaryMatch.groups[4]?.value?.trim()

            val amount = parseColombianAmount(rawAmount)
            if (amount == null || amount <= 0L) {
                return SmsPaymentParseResult.Ignored("No se pudo extraer un monto monetario válido en variante secundaria: '$rawAmount'")
            }

            if (!isValidPersonName(rawSenderName)) {
                return SmsPaymentParseResult.Ignored("El nombre del remitente no es válido en variante secundaria: '$rawSenderName'")
            }

            return SmsPaymentParseResult.Success(
                ParsedSmsPayment(
                    amount = amount,
                    senderName = rawSenderName!!,
                    receiverBusiness = null,
                    transactionDateTimeText = rawDateTime,
                    channel = rawChannel ?: "Nequi",
                    originatingAddress = originatingAddress,
                    rawBody = body
                )
            )
        }

        // 6. Evaluar con patrón terciario (<Nombre> te transfirió / te envió $<Monto>)
        val senderFirstMatch = NEQUI_SENDER_FIRST_PATTERN.matchEntire(normalized)
        if (senderFirstMatch != null) {
            val rawSenderName = cleanSenderName(senderFirstMatch.groups[1]?.value)
            val rawAmount = senderFirstMatch.groups[2]?.value

            val amount = parseColombianAmount(rawAmount)
            if (amount != null && amount > 0L && isValidPersonName(rawSenderName)) {
                return SmsPaymentParseResult.Success(
                    ParsedSmsPayment(
                        amount = amount,
                        senderName = rawSenderName!!,
                        receiverBusiness = null,
                        transactionDateTimeText = null,
                        channel = "Nequi",
                        originatingAddress = originatingAddress,
                        rawBody = body
                    )
                )
            }
        }

        return SmsPaymentParseResult.Ignored("El contenido del SMS no coincide con la estructura de notificación de pago de Nequi")
    }

    /**
     * Limpia el nombre del remitente eliminando sufijos bancarios residuales y espacios sobrantes.
     */
    fun cleanSenderName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var cleaned = raw.replace(Regex("\\s+"), " ").trim()
        cleaned = cleaned.replace(
            Regex("(?i)\\s+(?:exitosamente|con\\s+[eé]xito|exitoso|exitosa|(?:en|a)\\s+tu\\s+(?:cuenta(?:\\s+nequi)?|nequi(?:\\s+negocios)?)|hoy|ayer)$"),
            ""
        ).trim()
        return cleaned.ifBlank { null }
    }

    /**
     * Limpia el nombre del negocio receptor extrayendo la razón social limpia.
     * Ejemplo: "TECNICABLES, nombre ficticio" -> "TECNICABLES"
     */
    fun cleanBusinessName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",")
        val clean = parts[0].trim()
        return clean.ifBlank { null }
    }

    /**
     * Valida que una cadena represente un nombre de persona verosímil:
     * - Contiene al menos una palabra alfabética de 2 o más letras.
     * - No contiene palabras reservadas de notificaciones bancarias (ej. "exitosamente", "cuenta").
     * - No es un número, enlace web o código.
     */
    fun isValidPersonName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        if (trimmed.length < 2) return false

        // Debe contener únicamente letras y espacios (se toleran tildes y diéresis)
        val validCharsOnly = trimmed.matches(Regex("^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ\\s]+$"))
        if (!validCharsOnly) return false

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 6) return false

        if (words.any { it.lowercase() in NON_PERSON_WORDS }) return false

        // Debe tener al menos una palabra con más de una letra
        return words.any { it.length >= 2 }
    }

    /**
     * Interpreta de manera determinista montos monetarios en pesos colombianos:
     * - "$1" -> 1
     * - "$35.000" -> 35000 (el punto es separador de miles)
     * - "$1.250.000" -> 1250000
     * - "$35.000,00" -> 35000 (la coma es separador de decimales)
     * - "$50,000" -> 50000
     */
    fun parseColombianAmount(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace("$", "").trim()
        if (cleaned.isEmpty()) return null

        try {
            // Caso con decimales al final: ej. 35.000,00 o 35000.00
            val commaIndex = cleaned.lastIndexOf(',')
            val dotIndex = cleaned.lastIndexOf('.')

            var integerPart = cleaned
            if (commaIndex > dotIndex && commaIndex == cleaned.length - 3) {
                // Coma decimal con 2 dígitos: "35.000,00"
                integerPart = cleaned.substring(0, commaIndex)
            } else if (dotIndex > commaIndex && dotIndex == cleaned.length - 3) {
                // Punto decimal con 2 dígitos: "35000.00"
                integerPart = cleaned.substring(0, dotIndex)
            }

            // Remover todos los separadores de miles restantes
            val digitsOnly = integerPart.replace(".", "").replace(",", "").trim()
            if (digitsOnly.isEmpty() || !digitsOnly.all { it.isDigit() }) return null

            return digitsOnly.toLong()
        } catch (e: Exception) {
            try {
                Log.w(TAG, "Error interpretando monto '$raw': ${e.message}")
            } catch (_: Throwable) {
                // Ignorar en pruebas unitarias JVM donde android.util.Log no está mockeado
            }
            return null
        }
    }
}
