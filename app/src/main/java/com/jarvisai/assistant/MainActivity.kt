package com.jarvisai.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private val speechRequest = 701
    private val permissionRequest = 702
    private var waitingForMicPermission = false
    private val wakePermissionRequest = 703

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Jarvis")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        handleWakeIntent(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
        }
    }

    private fun startSpeechInput() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "বলুন, JARVIS শুনছে")
            }
            startActivityForResult(i, speechRequest)
        } catch (_: Exception) {
            web.evaluateJavascript("window.onJarvisSpeechError && window.onJarvisSpeechError()", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun startWakeWord() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val serviceIntent = Intent(this@MainActivity, WakeWordService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                } else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), wakePermissionRequest)
            }
        }
        @JavascriptInterface fun stopWakeWord() {
            stopService(Intent(this@MainActivity, WakeWordService::class.java))
        }
        @JavascriptInterface fun speak(text: String) {
            runOnUiThread { tts?.speak(text.take(2500), TextToSpeech.QUEUE_FLUSH, null, "jarvis") }
        }
        @JavascriptInterface fun listen() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startSpeechInput()
                } else {
                    waitingForMicPermission = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
                }
            }
        }
        @JavascriptInterface fun ask(provider: String, prompt: String, key: String, model: String) {
            thread {
                try {
                    val answer = when (provider.lowercase()) {
                        "openai" -> callOpenAI(prompt, key, model.ifBlank { "gpt-4o-mini" })
                        "gemini" -> callGemini(prompt, key, model.ifBlank { "gemini-2.0-flash" })
                        "claude" -> callClaude(prompt, key, model.ifBlank { "claude-3-5-haiku-latest" })
                        else -> "এই provider এখনো সংযুক্ত নয়। OpenAI, Gemini অথবা Claude বেছে নিন।"
                    }
                    val safe = JSONObject.quote(answer)
                    runOnUiThread { web.evaluateJavascript("window.onJarvisAnswer($safe)", null) }
                } catch (e: Exception) {
                    val safe = JSONObject.quote("সমস্যা হয়েছে: ${e.message ?: "নেটওয়ার্ক বা API key পরীক্ষা করুন"}")
                    runOnUiThread { web.evaluateJavascript("window.onJarvisAnswer($safe)", null) }
                }
            }
        }
    }

    private fun post(url: String, headers: Map<String,String>, body: JSONObject): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.requestMethod = "POST"; c.connectTimeout = 25000; c.readTimeout = 60000
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val result = stream.bufferedReader().use { it.readText() }
        if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}: $result")
        return JSONObject(result)
    }
    private fun callOpenAI(p: String, k: String, m: String): String {
        require(k.isNotBlank()) { "OpenAI API key দিন Settings-এ" }
        val b = JSONObject().put("model",m).put("messages", JSONArray().put(JSONObject().put("role","user").put("content",p)))
        return post("https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer $k"), b)
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
    private fun callGemini(p: String, k: String, m: String): String {
        require(k.isNotBlank()) { "Gemini API key দিন Settings-এ" }
        val b = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",p)))))
        return post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$k", emptyMap(), b)
            .getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }
    private fun callClaude(p: String, k: String, m: String): String {
        require(k.isNotBlank()) { "Claude API key দিন Settings-এ" }
        val b = JSONObject().put("model",m).put("max_tokens",1200).put("messages",JSONArray().put(JSONObject().put("role","user").put("content",p)))
        return post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to k, "anthropic-version" to "2023-06-01"), b)
            .getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("JARVIS_WAKE", false) == true) {
            intent.removeExtra("JARVIS_WAKE")
            web.postDelayed({ web.evaluateJavascript("window.onJarvisWake && window.onJarvisWake()", null) }, 700)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechRequest) {
            val spoken = if (resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty() else ""
            web.evaluateJavascript("window.onJarvisSpeech(${JSONObject.quote(spoken)})", null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == wakePermissionRequest && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val serviceIntent = Intent(this, WakeWordService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        }
        if (requestCode == permissionRequest && waitingForMicPermission) {
            waitingForMicPermission = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startSpeechInput()
            else web.evaluateJavascript("window.onJarvisSpeechError && window.onJarvisSpeechError()", null)
        }
    }

    override fun onDestroy() { tts?.shutdown(); web.destroy(); super.onDestroy() }
}
