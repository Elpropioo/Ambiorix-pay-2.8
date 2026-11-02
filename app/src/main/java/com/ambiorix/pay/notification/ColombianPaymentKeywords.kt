package com.ambiorix.pay.notification

import java.text.Normalizer
import java.util.Locale

/**
 * Palabras clave centralizadas para la detección de pagos en Colombia.
 * Fáciles de ajustar y extender según las actualizaciones de las apps bancarias.
 */
object ColombianPaymentKeywords {

    /**
     * Palabras que indican que entró un pago/transferencia (en minúsculas, sin tildes).
     */
    val INCOMING_PAYMENT_KEYWORDS: List<String> = listOf(
        "recibiste",
        "has recibido",
        "te enviaron",
        "te enviaron plata",
        "te llego",
        "te pagaron",
        "te transfirieron",
        "te consignaron",
        "recibio",
        "abono",
        "pago recibido",
        "recibiste una transferencia",
        "te paso plata",
        "recibiste plata",
        "te envio",
        "entrada de dinero",
        "te acaban de enviar"
    )

    /**
     * Palabras que indican una transacción saliente, código OTP, cobro o publicidad.
     * Si alguna de estas palabras aparece en el mensaje, la notificación se descarta de inmediato.
     */
    val EXCLUSION_KEYWORDS: List<String> = listOf(
        "enviaste",
        "has enviado",
        "pagaste",
        "retiraste",
        "transferiste",
        "sacaste",
        "compra",
        "compraste",
        "codigo",
        "clave dinamica",
        "tu codigo",
        "promocion",
        "publicidad",
        "oferta",
        "prestamo",
        "credito aprobado",
        "seguridad",
        "inicio de sesion",
        "intento",
        "fallido",
        "rechazado",
        "cancelado",
        "devuelto",
        "pago no exitoso",
        "no pudimos",
        "actualiza tu app"
    )

    /**
     * Palabras asociadas a saldo posterior o costos informativos que deben ignorarse
     * al buscar el monto real recibido.
     */
    val BALANCE_KEYWORDS: List<String> = listOf(
        "saldo",
        "tu nuevo saldo",
        "saldo disponible",
        "disponible",
        "comision",
        "costo",
        "tarifa",
        "iva",
        "retencion"
    )

    /**
     * Normaliza un texto removiendo acentos diacríticos (tildes) y convirtiendo a minúsculas
     * para facilitar la comparación exacta e insensible al caso.
     */
    fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutAccents = decomposed.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return withoutAccents.lowercase(Locale.ROOT).trim()
    }
}
