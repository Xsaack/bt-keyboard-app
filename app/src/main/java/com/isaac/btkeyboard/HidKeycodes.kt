package com.isaac.btkeyboard

// Códigos de tecla HID estándar (USB HID Usage Tables) y su reporte de 8 bytes.
// Byte 0 = modificadores (bit1 = Shift izquierdo), Byte 1 = reservado, Bytes 2-7 = hasta 6 teclas.

object HidKeycodes {
    const val MOD_NONE = 0x00
    const val MOD_SHIFT = 0x02

    // Descriptor HID estándar de teclado (boilerplate del spec USB HID, usado en incontables
    // implementaciones de referencia).
    val KEYBOARD_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01,                          // Usage Page (Generic Desktop)
        0x09, 0x06,                          // Usage (Keyboard)
        0xA1.toByte(), 0x01,                 // Collection (Application)
        0x05, 0x07,                          //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),                 //   Usage Minimum (224)
        0x29, 0xE7.toByte(),                 //   Usage Maximum (231)
        0x15, 0x00,                          //   Logical Minimum (0)
        0x25, 0x01,                          //   Logical Maximum (1)
        0x75, 0x01,                          //   Report Size (1)
        0x95.toByte(), 0x08,                 //   Report Count (8)
        0x81.toByte(), 0x02,                 //   Input (Data, Var, Abs) - modificadores
        0x95.toByte(), 0x01,
        0x75, 0x08,
        0x81.toByte(), 0x01,                 //   Input (Const) - reservado
        0x95.toByte(), 0x05,
        0x75, 0x01,
        0x05, 0x08,                          //   Usage Page (LEDs)
        0x19, 0x01,
        0x29, 0x05,
        0x91.toByte(), 0x02,                 //   Output (LEDs)
        0x95.toByte(), 0x01,
        0x75, 0x03,
        0x91.toByte(), 0x01,                 //   Output (Const)
        0x95.toByte(), 0x06,
        0x75, 0x08,
        0x15, 0x00,
        0x25, 0x65,
        0x05, 0x07,
        0x19, 0x00,
        0x29, 0x65,
        0x81.toByte(), 0x00,                 //   Input (Data, Array) - hasta 6 teclas
        0xC0.toByte()                        // End Collection
    )

    // Devuelve (modificador, keycode) para un caracter simple (dígitos, letras, espacio, enter).
    fun forChar(c: Char): Pair<Int, Int>? {
        return when {
            c in '1'..'9' -> MOD_NONE to (0x1E + (c - '1'))
            c == '0' -> MOD_NONE to 0x27
            c in 'a'..'z' -> MOD_NONE to (0x04 + (c - 'a'))
            c in 'A'..'Z' -> MOD_SHIFT to (0x04 + (c - 'A'))
            c == ' ' -> MOD_NONE to 0x2C
            c == '\n' -> MOD_NONE to 0x28 // Enter
            else -> null
        }
    }

    fun keyDownReport(modifier: Int, keycode: Int): ByteArray =
        byteArrayOf(modifier.toByte(), 0x00, keycode.toByte(), 0, 0, 0, 0, 0)

    val keyUpReport: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
}
