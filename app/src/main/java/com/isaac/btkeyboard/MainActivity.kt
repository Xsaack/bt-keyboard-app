package com.isaac.btkeyboard

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var bondedList: List<BluetoothDevice> = emptyList()

    private lateinit var statusText: TextView
    private lateinit var deviceListView: ListView

    // --- Voz ---
    private lateinit var voiceStatus: TextView
    private lateinit var articleListView: ListView
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var voiceModeActive = false
    private enum class Modo { NORMAL, PREGUNTAR_NOMBRE, PREGUNTAR_CODIGO, EDITAR_NOMBRE, EDITAR_CODIGO }
    private var modo = Modo.NORMAL
    private var nombrePendiente: String? = null
    private var articulosActuales: List<Map.Entry<String, String>> = emptyList()

    private val executor = Executors.newSingleThreadExecutor()

    private val REQUEST_PERMS = 100
    private val REQUEST_DISCOVERABLE = 101

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            runOnUiThread {
                setStatus(if (registered) "Registrado como teclado. Ahora hazte visible y empareja desde la PC." else "No se pudo registrar.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            runOnUiThread {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        setStatus("Conectado a ${device?.name ?: device?.address} como teclado ✔")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedDevice == device) connectedDevice = null
                        setStatus("Desconectado.")
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, HidKeycodes.keyUpReport)
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {}
        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {}
        override fun onVirtualCableUnplug(device: BluetoothDevice?) {}
        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {}
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerHidApp()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceListView = findViewById(R.id.deviceList)
        voiceStatus = findViewById(R.id.voiceStatus)
        articleListView = findViewById(R.id.articleList)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        requestBtPermissions()

        findViewById<Button>(R.id.btnRegister).setOnClickListener { connectHidProfile() }
        findViewById<Button>(R.id.btnDiscoverable).setOnClickListener { makeDiscoverable() }
        findViewById<Button>(R.id.btnRefreshDevices).setOnClickListener { refreshDeviceList() }
        findViewById<Button>(R.id.btnSendTest).setOnClickListener {
            val text = findViewById<EditText>(R.id.testText).text.toString()
            sendString(text)
        }

        findViewById<Button>(R.id.btnVoiceToggle).setOnClickListener {
            if (voiceModeActive) detenerVoz() else iniciarVoz()
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("es", "MX")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (voiceModeActive) runOnUiThread { escucharUnaVez() }
            }
        })

        refreshDeviceList()
        refreshArticleList()
    }

    // ---------------- Control por voz ----------------

    private var yaResuelto = false // evita procesar el mismo resultado dos veces (parcial + final)

    private var usandoOffline = false
    private var watchdogToken: Runnable? = null

    // Usa el reconocedor "en el dispositivo" (offline real, Android 13+) si está disponible
    // y el paquete de español ya está descargado; si no, usa el normal (en línea).
    private fun crearRecognizer(offline: Boolean): SpeechRecognizer {
        usandoOffline = offline
        voiceStatus.text = if (offline) "Usando reconocimiento en el dispositivo (offline)."
                            else "Usando reconocimiento en línea."
        return if (offline) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun hayOfflineDisponible(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun cambiarAOnline() {
        if (!usandoOffline) return // ya está en línea, no hay a qué cambiar
        voiceStatus.text = "El modo offline no respondió, cambiando a en línea..."
        speechRecognizer?.destroy()
        speechRecognizer = crearRecognizer(offline = false)
        speechRecognizer?.setRecognitionListener(crearListener())
        escucharUnaVez()
    }

    private fun crearListener(): RecognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            cancelarWatchdog()
            if (yaResuelto) return
            val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            yaResuelto = true
            procesarTexto(texto)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            cancelarWatchdog()
            if (yaResuelto || modo != Modo.NORMAL) return
            val texto = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            val normalizado = ArticleStore.normalize(texto)
            if (normalizado.contains("nuevo articulo") || normalizado.contains("editar articulo")) return
            val map = ArticleStore.getAll(this@MainActivity)
            val match = map.entries.firstOrNull { normalizado.contains(it.key) }
            if (match != null) {
                yaResuelto = true
                speechRecognizer?.cancel()
                procesarTexto(texto)
            }
        }
        override fun onError(error: Int) {
            cancelarWatchdog()
            if (voiceModeActive) escucharUnaVez()
        }
        override fun onReadyForSpeech(params: Bundle?) { cancelarWatchdog() }
        override fun onBeginningOfSpeech() { cancelarWatchdog() }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun cancelarWatchdog() {
        watchdogToken?.let { android.os.Handler(mainLooper).removeCallbacks(it) }
        watchdogToken = null
    }

    private fun iniciarVoz() {
        modo = Modo.NORMAL
        voiceModeActive = true
        findViewById<Button>(R.id.btnVoiceToggle).text = "Detener reconocimiento por voz"
        voiceStatus.text = "Escuchando..."
        if (speechRecognizer == null) {
            speechRecognizer = crearRecognizer(offline = hayOfflineDisponible())
            speechRecognizer?.setRecognitionListener(crearListener())
        }
        escucharUnaVez()
    }

    private fun detenerVoz() {
        voiceModeActive = false
        speechRecognizer?.stopListening()
        findViewById<Button>(R.id.btnVoiceToggle).text = "6. Iniciar reconocimiento por voz"
        voiceStatus.text = "Detenido."
    }

    private fun escucharUnaVez() {
        if (!voiceModeActive) return
        yaResuelto = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Recorta la espera de silencio tras hablar (por defecto ronda los 2 segundos)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
        }
        // Pequeño respiro para que el reconocedor anterior termine de cerrarse
        // y no choque con el nuevo (evita el error "recognizer busy" que retrasa todo).
        android.os.Handler(mainLooper).postDelayed({
            if (voiceModeActive) speechRecognizer?.startListening(intent)
        }, 120)

        // Vigilante: si el motor offline no da ninguna señal de vida en 4 segundos,
        // se asume roto en este equipo y se cambia permanentemente a en línea.
        cancelarWatchdog()
        if (usandoOffline) {
            val token = Runnable { if (voiceModeActive) cambiarAOnline() }
            watchdogToken = token
            android.os.Handler(mainLooper).postDelayed(token, 4000)
        }
    }

    private fun decir(texto: String) {
        voiceStatus.text = texto
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "id1")
    }

    private fun procesarTexto(textoOriginal: String) {
        val texto = ArticleStore.normalize(textoOriginal)

        when (modo) {
            Modo.NORMAL -> {
                if (texto.contains("nuevo articulo")) {
                    modo = Modo.PREGUNTAR_NOMBRE
                    decir("¿Cuál es el nombre del artículo?")
                    return
                }
                if (texto.contains("editar articulo")) {
                    modo = Modo.EDITAR_NOMBRE
                    decir("¿Qué artículo quieres editar?")
                    return
                }
                val map = ArticleStore.getAll(this)
                val match = map.entries.firstOrNull { texto.contains(it.key) }
                if (match != null) {
                    sendString(match.value + "\n")
                    voiceStatus.text = "\"$texto\" → ${match.value} + Enter"
                } else {
                    voiceStatus.text = "No reconocido: \"$texto\""
                }
                if (voiceModeActive) escucharUnaVez()
            }

            Modo.PREGUNTAR_NOMBRE -> {
                nombrePendiente = texto
                modo = Modo.PREGUNTAR_CODIGO
                decir("¿Cuál es el código de $texto?")
            }

            Modo.PREGUNTAR_CODIGO -> {
                val codigo = NumberParser.extractNumber(texto)
                if (codigo != null && nombrePendiente != null) {
                    ArticleStore.addOrUpdate(this, nombrePendiente!!, codigo)
                    decir("Guardado. ${nombrePendiente} con código $codigo")
                    runOnUiThread { refreshArticleList() }
                    modo = Modo.NORMAL
                    nombrePendiente = null
                } else {
                    decir("No entendí el código, dilo de nuevo")
                }
            }

            Modo.EDITAR_NOMBRE -> {
                val map = ArticleStore.getAll(this)
                val match = map.entries.firstOrNull { texto.contains(it.key) }
                if (match != null) {
                    nombrePendiente = match.key
                    modo = Modo.EDITAR_CODIGO
                    decir("¿Cuál es el nuevo código de ${match.key}?")
                } else {
                    decir("No encontré ese artículo. Dime otra vez cuál quieres editar, o di cancelar.")
                    if (texto.contains("cancelar")) {
                        modo = Modo.NORMAL
                        nombrePendiente = null
                    }
                }
            }

            Modo.EDITAR_CODIGO -> {
                val codigo = NumberParser.extractNumber(texto)
                if (codigo != null && nombrePendiente != null) {
                    ArticleStore.addOrUpdate(this, nombrePendiente!!, codigo)
                    decir("Actualizado. ${nombrePendiente} ahora tiene código $codigo")
                    runOnUiThread { refreshArticleList() }
                    modo = Modo.NORMAL
                    nombrePendiente = null
                } else {
                    decir("No entendí el código, dilo de nuevo")
                }
            }
        }
    }

    private fun refreshArticleList() {
        val map = ArticleStore.getAll(this)
        articulosActuales = map.entries.toList()
        val items = articulosActuales.map { "${it.key} = ${it.value}" }
        articleListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        articleListView.setOnItemClickListener { _, _, position, _ ->
            val entry = articulosActuales[position]
            mostrarDialogoEditar(entry.key, entry.value)
        }
    }

    private fun mostrarDialogoEditar(nombreActual: String, codigoActual: String) {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        val nombreLabel = TextView(this)
        nombreLabel.text = "Nombre del artículo"
        val nombreInput = EditText(this)
        nombreInput.setText(nombreActual)

        val codigoLabel = TextView(this)
        codigoLabel.text = "Código"
        val codigoInput = EditText(this)
        codigoInput.setText(codigoActual)
        codigoInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        layout.addView(nombreLabel)
        layout.addView(nombreInput)
        layout.addView(codigoLabel)
        layout.addView(codigoInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Editar artículo")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val nuevoNombre = nombreInput.text.toString().trim()
                val nuevoCodigo = codigoInput.text.toString().trim()
                if (nuevoNombre.isNotEmpty() && nuevoCodigo.isNotEmpty()) {
                    if (ArticleStore.normalize(nuevoNombre) != ArticleStore.normalize(nombreActual)) {
                        ArticleStore.delete(this, nombreActual)
                    }
                    ArticleStore.addOrUpdate(this, nuevoNombre, nuevoCodigo)
                    refreshArticleList()
                }
            }
            .setNeutralButton("Eliminar") { _, _ ->
                ArticleStore.delete(this, nombreActual)
                refreshArticleList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setStatus(msg: String) {
        statusText.text = "Estado: $msg"
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestBtPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        val toRequest = perms.filter { !hasPermission(it) }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), REQUEST_PERMS)
        }
    }

    private fun connectHidProfile() {
        setStatus("Conectando al perfil HID...")
        bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "VoiceKeyboard",
            "Teclado controlado por voz",
            "Isaac",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HidKeycodes.KEYBOARD_DESCRIPTOR
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, 11250
        )
        hidDevice?.registerApp(sdp, null, qos, executor, hidCallback)
    }

    private fun makeDiscoverable() {
        val intent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        startActivity(intent)
    }

    private fun refreshDeviceList() {
        val bonded = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        bondedList = bonded
        val names = bonded.map { "${it.name}\n${it.address}" }
        deviceListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val device = bondedList[position]
            setStatus("Conectando a ${device.name}...")
            hidDevice?.connect(device)
        }
    }

    private fun sendString(text: String) {
        val device = connectedDevice
        if (device == null) {
            setStatus("No hay ningún dispositivo conectado todavía.")
            return
        }
        Thread {
            for (c in text) {
                val pair = HidKeycodes.forChar(c) ?: continue
                val (mod, keycode) = pair
                hidDevice?.sendReport(device, 0, HidKeycodes.keyDownReport(mod, keycode))
                Thread.sleep(25)
                hidDevice?.sendReport(device, 0, HidKeycodes.keyUpReport)
                Thread.sleep(25)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}
