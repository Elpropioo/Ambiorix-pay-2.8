package com.ambiorix.pay.data

data class BankingAppInfo(
    val id: String,
    val name: String,
    val primaryPackage: String,
    val alternativePackages: List<String> = emptyList(),
    val notes: String = ""
)

object KnownApps {
    /**
     * Paquetes de aplicaciones de pago en Colombia.
     * Se proveen los nombres de paquete reales verificados en Google Play Store.
     */
    val DEFAULT_APPS = listOf(
        BankingAppInfo(
            id = "nequi",
            name = "Nequi",
            primaryPackage = "com.nequi.MobileApp",
            notes = "Verificado en Google Play Store."
        ),
        BankingAppInfo(
            id = "bancolombia_personas",
            name = "Bancolombia Personas",
            primaryPackage = "com.bancolombia.personas.gemini",
            alternativePackages = listOf("com.todo1.mobile"),
            notes = "com.bancolombia.personas.gemini es la versión moderna. com.todo1.mobile es la versión anterior."
        ),
        BankingAppInfo(
            id = "bancolombia_a_la_mano",
            name = "Bancolombia A la mano",
            primaryPackage = "com.bancolombia.billetera",
            notes = "Billetera A la mano."
        ),
        BankingAppInfo(
            id = "daviplata",
            name = "Daviplata",
            primaryPackage = "com.davivienda.daviplataapp",
            alternativePackages = listOf("com.davivienda.daviplata"),
            notes = "Billetera electrónica de Davivienda."
        ),
        BankingAppInfo(
            id = "dale",
            name = "Dale!",
            primaryPackage = "co.com.bancobogota.dale",
            notes = "Billetera digital de Grupo Aval."
        ),
        BankingAppInfo(
            id = "movii",
            name = "Movii",
            primaryPackage = "com.movii",
            notes = "SEDPE billetera digital."
        ),
        BankingAppInfo(
            id = "lulo",
            name = "Lulo Bank",
            primaryPackage = "com.lulobank.mobile",
            notes = "Neobanco."
        ),
        BankingAppInfo(
            id = "mensajes_sms_nequi",
            name = "Mensajes SMS (Nequi 890806 / Bre-B)",
            primaryPackage = "com.google.android.apps.messaging",
            alternativePackages = listOf(
                "com.samsung.android.messaging",
                "com.android.mms",
                "com.miui.mms",
                "com.huawei.message",
                "com.motorola.messaging",
                "com.coloros.mms",
                "com.oneplus.mms"
            ),
            notes = "Detecta confirmaciones de pago de Nequi (890806 / 85954) y Bre-B que llegan por mensaje de texto SMS."
        )
    )

    val SMS_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.miui.mms",
        "com.huawei.message",
        "com.motorola.messaging",
        "com.coloros.mms",
        "com.oneplus.mms"
    )

    val DEFAULT_ENABLED_PACKAGES: Set<String> = setOf(
        "com.nequi.MobileApp",
        "com.bancolombia.personas.gemini",
        "com.todo1.mobile",
        "com.bancolombia.billetera",
        "com.davivienda.daviplataapp",
        "com.davivienda.daviplata"
    ) + SMS_PACKAGES

    fun isSmsPackage(packageName: String): Boolean {
        return SMS_PACKAGES.any { it.equals(packageName, ignoreCase = true) }
    }

    fun getDisplayName(packageName: String): String {
        if (isSmsPackage(packageName)) {
            return "Nequi SMS"
        }
        for (app in DEFAULT_APPS) {
            if (app.primaryPackage.equals(packageName, ignoreCase = true) ||
                app.alternativePackages.any { it.equals(packageName, ignoreCase = true) }
            ) {
                return app.name
            }
        }
        // Fallback: mostrar la última sección del package name o el package name completo
        val parts = packageName.split(".")
        return if (parts.isNotEmpty()) parts.last().replaceFirstChar { it.uppercase() } else packageName
    }
}
