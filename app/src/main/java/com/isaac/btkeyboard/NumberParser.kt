package com.isaac.btkeyboard

// Convierte números dichos en español ("treinta y dos") a texto numérico ("32").
object NumberParser {

    private val unidades = mapOf(
        "cero" to 0, "uno" to 1, "un" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
        "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
        "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14,
        "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18,
        "diecinueve" to 19, "veinte" to 20, "veintiuno" to 21, "veintidos" to 22,
        "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25, "veintiseis" to 26,
        "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29
    )
    private val decenas = mapOf(
        "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60,
        "setenta" to 70, "ochenta" to 80, "noventa" to 90
    )
    private val centenas = mapOf(
        "cien" to 100, "ciento" to 100, "doscientos" to 200, "trescientos" to 300,
        "cuatrocientos" to 400, "quinientos" to 500, "seiscientos" to 600,
        "setecientos" to 700, "ochocientos" to 800, "novecientos" to 900
    )

    // Devuelve el número como String de dígitos, o null si no se pudo interpretar.
    fun extractNumber(texto: String): String? {
        // Si el reconocedor ya devolvió dígitos (ej. "24"), úsalos directo.
        val digits = Regex("\\d+").find(texto)
        if (digits != null) return digits.value

        val tokens = texto.lowercase().split(" ").filter { it.isNotBlank() }
        var total = 0
        var actual = 0
        var encontrado = false

        for (token in tokens) {
            when {
                token == "y" -> continue
                token == "mil" -> {
                    total += (if (actual == 0) 1 else actual) * 1000
                    actual = 0
                    encontrado = true
                }
                centenas.containsKey(token) -> { actual += centenas.getValue(token); encontrado = true }
                decenas.containsKey(token) -> { actual += decenas.getValue(token); encontrado = true }
                unidades.containsKey(token) -> { actual += unidades.getValue(token); encontrado = true }
                else -> { /* palabra desconocida, se ignora */ }
            }
        }
        total += actual
        return if (encontrado) total.toString() else null
    }
}
