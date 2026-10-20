package com.jarvis.redmi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var chatColumn: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var statusText: TextView
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var busy = false
    private val history = mutableListOf<Pair<String, String>>()

    private val prefs by lazy { getSharedPreferences("jarvis_private", MODE_PRIVATE) }
    private val keyAlias = "jarvis_groq_key_aes"
    private val micRequestCode = 7301
    private val systemPrompt = """
        Ты JARVIS — русскоязычный голосовой помощник пользователя. Отвечай по-русски, дружелюбно, прямо и понятно. Пользователь предпочитает неформальное общение. Не утверждай, что выполнил действие на телефоне, если приложение его реально не выполняло. Если просьба требует доступа к приложению или сети, объясни ограничение. Ответы делай умеренной длины и удобными для озвучивания вслух.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 20, 29)
        window.navigationBarColor = Color.rgb(16, 20, 29)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tts = TextToSpeech(this, this)
        buildUi()
        addMessage("JARVIS онлайн. Нажми 🎙️ и скажи команду или напиши сообщение.\n\nБез API-ключа работают простые локальные команды; для умных ответов нужен ключ Groq.", false)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(Color.rgb(16, 20, 29))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 25f
            setTextColor(Color.rgb(226, 246, 255))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        statusText = TextView(this).apply {
            text = "ГОТОВ К РАБОТЕ"
            textSize = 11f
            setTextColor(Color.rgb(86, 217, 255))
        }
        titleBox.addView(title)
        titleBox.addView(statusText)
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val settingsButton = button("⚙ API", Color.rgb(37, 53, 69))
        settingsButton.setOnClickListener { showApiKeyDialog() }
        header.addView(settingsButton)
        root.addView(header)

        val divider = View(this).apply { setBackgroundColor(Color.rgb(44, 60, 76)) }
        val dividerParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        dividerParams.topMargin = dp(12)
        dividerParams.bottomMargin = dp(10)
        root.addView(divider, dividerParams)

        chatScroll = ScrollView(this).apply { isFillViewport = true }
        chatColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(12))
        }
        chatScroll.addView(chatColumn)
        root.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Скажи или напиши что-нибудь…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(154, 168, 184))
            textSize = 15f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(32, 41, 54), 18)
            maxLines = 3
        }
        controls.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        val mic = button("🎙️", Color.rgb(36, 95, 122))
        val micParams = LinearLayout.LayoutParams(dp(54), dp(52))
        micParams.leftMargin = dp(6)
        controls.addView(mic, micParams)
        mic.setOnClickListener { startListening() }
        val send = button("➤", Color.rgb(0, 137, 176))
        val sendParams = LinearLayout.LayoutParams(dp(54), dp(52))
        sendParams.leftMargin = dp(6)
        controls.addView(send, sendParams)
        send.setOnClickListener { sendMessage() }
        root.addView(controls)

        val foot = TextView(this).apply {
            text = "Голос: русский • Ответы ИИ требуют доступной сети и ключа Groq"
            textSize = 10f
            setTextColor(Color.rgb(122, 140, 158))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(foot)
        setContentView(root)
    }

    private fun button(label: String, color: Int): Button = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = rounded(color, 14)
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun addMessage(text: String, fromUser: Boolean) {
        val bubble = TextView(this).apply {
            this.text = (if (fromUser) "ТЫ\n" else "JARVIS\n") + text
            textSize = 15f
            setTextColor(if (fromUser) Color.rgb(240, 251, 255) else Color.rgb(221, 231, 241))
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = rounded(if (fromUser) Color.rgb(24, 86, 111) else Color.rgb(34, 43, 57), 16)
            setTextIsSelectable(true)
        }
        val params = LinearLayout.LayoutParams(
            if (fromUser) (resources.displayMetrics.widthPixels * 0.83f).toInt() else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(5)
        params.bottomMargin = dp(5)
        if (fromUser) params.gravity = Gravity.END
        else params.gravity = Gravity.START
        chatColumn.addView(bubble, params)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text.uppercase(Locale.ROOT) }
    }

    private fun sendMessage() {
        if (busy) {
            Toast.makeText(this, "Подожди, я ещё отвечаю", Toast.LENGTH_SHORT).show()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) { }
        addMessage(text, true)

        val localAnswer = handleLocalCommand(text)
        if (localAnswer != null) {
            addMessage(localAnswer, false)
            speak(localAnswer)
            setStatus("ГОТОВ К РАБОТЕ")
            return
        }

        val apiKey = loadApiKey()
        if (apiKey.isNullOrBlank()) {
            addMessage("Для умных ответов нужен ключ Groq. Нажми «⚙ API», вставь ключ из console.groq.com и повтори запрос. Локальные команды вроде «который час» работают без него.", false)
            setStatus("НУЖЕН API-КЛЮЧ")
            showApiKeyDialog()
            return
        }

        busy = true
        setStatus("ДУМАЮ…")
        val sendButtonMessage = "Секунду, обрабатываю запрос…"
        addMessage(sendButtonMessage, false)
        val loadingView = chatColumn.getChildAt(chatColumn.childCount - 1) as? TextView
        Thread {
            val result = try {
                requestGroq(apiKey, text)
            } catch (e: Exception) {
                "Не смог получить ответ от ИИ: ${friendlyError(e)}"
            }
            runOnUiThread {
                if (loadingView != null) chatColumn.removeView(loadingView)
                busy = false
                setStatus("ГОТОВ К РАБОТЕ")
                addMessage(result, false)
                history.add(text to result)
                if (history.size > 12) history.removeAt(0)
                speak(result)
            }
        }.start()
    }

    private fun handleLocalCommand(raw: String): String? {
        val text = raw.lowercase(Locale.ROOT).trim().replace(Regex("[.!?]+$"), "").trim()
        when (text) {
            "который час", "сколько времени", "скажи время", "какое сейчас время" ->
                return "Сейчас ${SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date())}."
            "какая сегодня дата", "какое сегодня число", "скажи дату", "сегодняшняя дата" ->
                return "Сегодня ${SimpleDateFormat("d MMMM yyyy года", Locale("ru", "RU")).format(Date())}."
            "помощь", "что ты умеешь", "список команд" ->
                return "Без интернета я могу сказать время и дату, открыть YouTube, Telegram, браузер и настройки телефона. Для остальных вопросов нужен ключ Groq и доступ к API."
            "открой youtube", "открой ютуб", "запусти youtube", "запусти ютуб" -> {
                openAppOrWeb("com.google.android.youtube", "https://www.youtube.com/")
                return "Открываю YouTube."
            }
            "открой telegram", "открой телеграм", "запусти telegram", "запусти телеграм" -> {
                openAppOrWeb("org.telegram.messenger", "https://t.me/")
                return "Открываю Telegram."
            }
            "открой настройки", "настройки телефона" -> {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
                return "Открываю настройки телефона."
            }
            "открой браузер", "запусти браузер" -> {
                openAppOrWeb("", "https://www.google.com/")
                return "Открываю браузер."
            }
        }
        return null
    }

    private fun openAppOrWeb(packageName: String, url: String) {
        try {
            val launch = if (packageName.isNotBlank()) packageManager.getLaunchIntentForPackage(packageName) else null
            if (launch != null) startActivity(launch)
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Не получилось открыть приложение или ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestGroq(apiKey: String, currentText: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.takeLast(12).forEach { (user, assistant) ->
            messages.put(JSONObject().put("role", "user").put("content", user))
            messages.put(JSONObject().put("role", "assistant").put("content", assistant))
        }
        messages.put(JSONObject().put("role", "user").put("content", currentText))

        val payload = JSONObject()
            .put("model", "llama-3.3-70b-versatile")
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 700)

        val connection = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException("HTTP $code${if (!detail.isNullOrBlank()) ": $detail" else ""}")
            }
            val json = JSONObject(body)
            val answer = json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
            if (answer.isBlank()) throw IllegalStateException("API вернул пустой ответ")
            return answer
        } finally {
            connection.disconnect()
        }
    }

    private fun friendlyError(e: Exception): String {
        val detail = e.message.orEmpty()
        return when {
            detail.contains("HTTP 401") || detail.contains("invalid_api_key", true) -> "ключ API неверный. Проверь ключ в настройках."
            detail.contains("HTTP 429") -> "слишком много запросов или закончился лимит. Попробуй позже."
            detail.contains("Unable to resolve host", true) || detail.contains("failed to connect", true) || detail.contains("timeout", true) -> "нет соединения с API Groq. Проверь интернет; в некоторых сетях доступ к серверу может быть ограничен."
            detail.startsWith("HTTP ") -> "сервер вернул ошибку ($detail). Проверь ключ и доступность модели."
            else -> detail.ifBlank { "неизвестная ошибка" }
        }
    }

    private fun showApiKeyDialog() {
        val field = EditText(this).apply {
            hint = "gsk_…"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
            val link = TextView(this@MainActivity).apply {
                text = "Ключ создаётся на console.groq.com → API Keys"
                textSize = 12f
                setTextColor(Color.rgb(86, 217, 255))
                setPadding(0, dp(8), 0, dp(4))
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) } catch (_: Exception) { }
                }
            }
            addView(link)
        }
        AlertDialog.Builder(this)
            .setTitle("Ключ Groq API")
            .setMessage("Вставь свой ключ. Он хранится зашифрованным на этом телефоне и не вшивается в APK.")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val key = field.text.toString().trim()
                if (key.isNotBlank()) {
                    try {
                        saveApiKey(key)
                        Toast.makeText(this, "Ключ сохранён на телефоне", Toast.LENGTH_SHORT).show()
                        setStatus("КЛЮЧ СОХРАНЁН")
                    } catch (e: Exception) {
                        Toast.makeText(this, "Не удалось сохранить ключ: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun saveApiKey(key: String) {
        val secret = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val combined = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        prefs.edit().putString("encrypted_key", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    private fun loadApiKey(): String? {
        val saved = prefs.getString("encrypted_key", null) ?: return null
        return try {
            val combined = Base64.decode(saved, Base64.NO_WRAP)
            val ivLength = combined[0].toInt() and 0xff
            val iv = combined.copyOfRange(1, 1 + ivLength)
            val encrypted = combined.copyOfRange(1 + ivLength, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove("encrypted_key").apply()
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), micRequestCode)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addMessage("На телефоне нет доступной службы распознавания речи. Установи или включи службу голосового ввода в настройках Android.", false)
            return
        }
        try {
            if (speechRecognizer == null) speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { setStatus("СЛУШАЮ…") }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { setStatus("ОБРАБАТЫВАЮ РЕЧЬ…") }
                override fun onError(error: Int) {
                    setStatus("ГОТОВ К РАБОТЕ")
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал. Нажми на микрофон и попробуй ещё раз."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нужно разрешение на микрофон."
                        else -> "Не получилось распознать речь (код $error). Проверь службу голосового ввода и интернет."
                    }
                    addMessage(msg, false)
                }
                override fun onResults(results: Bundle?) {
                    setStatus("ГОТОВ К РАБОТЕ")
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) {
                        input.setText(spoken)
                        sendMessage()
                    } else addMessage("Не расслышал команду. Попробуй ещё раз.", false)
                }
                override fun onPartialResults(partialResults: Bundle?) { }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Слушаю команду для JARVIS")
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            setStatus("ГОТОВ К РАБОТЕ")
            addMessage("Не удалось запустить микрофон: ${e.message}", false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == micRequestCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[`*_#>]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isNotBlank()) tts?.speak(clean.take(3500), TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    override fun onDestroy() {
        try { speechRecognizer?.destroy() } catch (_: Exception) { }
        speechRecognizer = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null
        super.onDestroy()
    }
}
