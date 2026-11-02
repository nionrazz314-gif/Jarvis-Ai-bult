package com.jarvisai.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.WindowManager
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
    private var pageLoaded = false
    private var pendingWake = false
    private val wakePermissionRequest = 703
    private var wakeReceiverRegistered = false
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeWordService.ACTION_WAKE_DETECTED) {
                pendingWake = true
                if (pageLoaded) dispatchWakeToPage()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tts = TextToSpeech(this, this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportZoom(false)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                if (pendingWake) dispatchWakeToPage()
                // Start hands-free wake-word listening automatically after the UI opens.
                web.evaluateJavascript("window.autoStartWake && window.autoStartWake()", null)
            }
        }
        val wakeFilter = IntentFilter(WakeWordService.ACTION_WAKE_DETECTED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wakeReceiver, wakeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wakeReceiver, wakeFilter)
        }
        wakeReceiverRegistered = true
        web.addJavascriptInterface(Bridge(), "Jarvis")
        setContentView(web)
        web.requestFocus()
        web.loadUrl("file:///android_asset/index.html")
        handleWakeIntent(intent)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return

        // Android TTS cannot download Microsoft's NabanitaNeural voice by its ID.
        // If the device's installed TTS engine exposes that voice, prefer it;
        // otherwise choose an installed Bengali female voice, then bn-BD/bn-IN.
        val bengaliVoices = engine.voices?.filter {
            it.locale.language.equals("bn", ignoreCase = true)
        }.orEmpty()
        val savedName = getSharedPreferences("jarvis_voice", MODE_PRIVATE).getString("voice_name", null)
        val selectedVoice = bengaliVoices.firstOrNull { it.name == savedName }
            ?: bengaliVoices.firstOrNull {
                val name = it.name.lowercase(Locale.ROOT)
                name.contains("nabanita") || name.contains("tanishaa") ||
                    name.contains("female") || name.contains("woman") ||
                    name.contains("fem") || name.contains("bnf")
            }
            ?: bengaliVoices.firstOrNull { it.locale.country.equals("BD", ignoreCase = true) }
            ?: bengaliVoices.firstOrNull()

        if (selectedVoice != null) {
            engine.voice = selectedVoice
            engine.language = selectedVoice.locale
            getSharedPreferences("jarvis_voice", MODE_PRIVATE).edit().putString("voice_name", selectedVoice.name).apply()
        } else {
            val bdResult = engine.setLanguage(Locale("bn", "BD"))
            if (bdResult == TextToSpeech.LANG_MISSING_DATA ||
                bdResult == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale("bn", "IN"))
        }
        engine.setPitch(1.12f)
        engine.setSpeechRate(0.92f)
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

    private fun bengaliVoiceList(): List<android.speech.tts.Voice> =
        tts?.voices?.filter { it.locale.language.equals("bn", ignoreCase = true) }?.sortedBy { it.name } ?: emptyList()

        inner class Bridge {
        @JavascriptInterface fun startWakeWord() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val serviceIntent = Intent(this@MainActivity, WakeWordService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                } else requestPermissions(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO), wakePermissionRequest)
            }
        }
        @JavascriptInterface fun stopWakeWord() {
            stopService(Intent(this@MainActivity, WakeWordService::class.java))
        }
        @JavascriptInterface fun openExternal(rawUrl: String) {
            runOnUiThread {
                try {
                    val uri = Uri.parse(rawUrl)
                    val scheme = uri.scheme?.lowercase(Locale.ROOT)
                    if (scheme != "https" && scheme != "http") return@runOnUiThread
                    if (scheme == "http") return@runOnUiThread
                    val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                    val allowed = host == "www.google.com" ||
                        host == "www.facebook.com" ||
                        host == "wa.me" ||
                        host == "www.torproject.org" ||
                        host == "www.starlink.com" ||
                        host == "www.banglalink.net" || host == "banglalink.net" ||
                        host == "www.eutelsat.com" || host == "eutelsat.com" ||
                        host == "kacific.com" || host == "www.kacific.com" ||
                        host == "www.ses.com" || host == "ses.com" ||
                        host == "www.viasat.com" || host == "viasat.com" ||
                        host == "www.adntel.com" || host == "adntel.com"
                    if (!allowed) return@runOnUiThread
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    web.evaluateJavascript("alert('লিংকটি খোলা যায়নি।')", null)
                }
            }
        }
        @JavascriptInterface fun getNetworkStatus(): String {
            return try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                    ?: return "ফোনে active network নেই। Wi-Fi বা Mobile Data চালু করে আবার চেষ্টা করুন।"
                val caps = cm.getNetworkCapabilities(network)
                    ?: return "Network পাওয়া গেছে, কিন্তু Android connection details পড়তে পারেনি।"
                val types = mutableListOf<String>()
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types.add("Wi-Fi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types.add("Mobile data")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types.add("Ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types.add("VPN")
                if (android.os.Build.VERSION.SDK_INT >= 35 &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)) types.add("Satellite")

                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val connection = if (types.isEmpty()) "Network type অজানা" else types.joinToString(" + ")

                when {
                    !hasInternet -> "সংযোগ আছে ($connection), কিন্তু এই network Android-কে Internet access দেয়নি। Data/Wi-Fi বা operator settings পরীক্ষা করুন।"
                    !validated -> "সংযোগ আছে ($connection), কিন্তু Android এখনো Internet access verify করতে পারেনি। Captive portal, data limit বা operator সমস্যা হতে পারে।"
                    android.os.Build.VERSION.SDK_INT >= 35 &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) ->
                        "🛰️ Internet connected via Satellite transport."
                    else -> "✅ Internet connected: $connection. Jarvis এখন API server-এ request পাঠাতে পারবে।"
                }
            } catch (e: Exception) {
                "Network status পড়া যায়নি: " + (e.message ?: "unknown error")
            }
        }
        @JavascriptInterface fun speak(text: String) {
            runOnUiThread {
                tts?.setPitch(1.08f)
                tts?.setSpeechRate(0.94f)
                tts?.speak(text.take(2500), TextToSpeech.QUEUE_FLUSH, null, "jarvis")
            }
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
        @JavascriptInterface fun getBengaliVoices(): String {
            val arr = JSONArray()
            bengaliVoiceList().forEach { voice ->
                val n = voice.name.lowercase(Locale.ROOT)
                val likelyFemale = n.contains("female") || n.contains("woman") || n.contains("fem") ||
                    n.contains("nabanita") || n.contains("tanishaa") || n.contains("bnf")
                arr.put(JSONObject().put("name", voice.name)
                    .put("label", voice.name + if (likelyFemale) " — সম্ভাব্য Female" else ""))
            }
            return arr.toString()
        }
        @JavascriptInterface fun selectBengaliVoice(name: String): String {
            val selected = bengaliVoiceList().firstOrNull { it.name == name }
                ?: return "এই voice ফোনে পাওয়া যায়নি।"
            runOnUiThread {
                tts?.voice = selected
                tts?.language = selected.locale
                tts?.setPitch(1.12f)
                tts?.setSpeechRate(0.92f)
                getSharedPreferences("jarvis_voice", MODE_PRIVATE).edit().putString("voice_name", selected.name).apply()
            }
            val n = selected.name.lowercase(Locale.ROOT)
            val female = n.contains("female") || n.contains("woman") || n.contains("fem") ||
                n.contains("nabanita") || n.contains("tanishaa") || n.contains("bnf")
            return if (female) "সম্ভাব্য Female voice নির্বাচন করা হয়েছে: ${selected.name}" else
                "Voice নির্বাচন করা হয়েছে: ${selected.name}। নাম দেখে Female কিনা নিশ্চিত হওয়া যাচ্ছে না।"
        }
        @JavascriptInterface fun ask(provider: String, prompt: String, key: String, model: String) {
            thread {
                try {
                    val answer = when (provider.lowercase()) {
                        "groq" -> callGroq(prompt, key, model.ifBlank { "openai/gpt-oss-20b" })
                        "openai" -> callOpenAI(prompt, key, model.ifBlank { "gpt-4o-mini" })
                        "gemini" -> callGemini(prompt, key, model.ifBlank { "gemini-2.5-flash" })
                        "claude" -> callClaude(prompt, key, model.ifBlank { "claude-3-5-haiku-latest" })
                        else -> "এই provider এখনো সংযুক্ত নয়। Groq, OpenAI, Gemini অথবা Claude বেছে নিন।"
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
    private fun callGroq(p: String, k: String, m: String): String {
        require(k.isNotBlank()) { "Groq API key দিন Settings-এ" }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "You are Jarvis AI, a warm and intelligent personal assistant. Reply in natural, fluent Bangladeshi Bengali by default, using clear everyday কথ্য বাংলা and a friendly respectful tone. Match the user's language if they clearly use another language. Keep spoken answers smooth and easy to understand; avoid awkward translations and unnecessary English."))
            .put(JSONObject().put("role","user").put("content",p))
        val b = JSONObject().put("model",m).put("messages",messages)
        return post("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer $k"), b)
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
    private fun callGemini(p: String, k: String, m: String): String {
        require(k.isNotBlank()) { "Gemini API key দিন Settings-এ" }
        val b = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",p)))))
        return post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", mapOf("x-goog-api-key" to k), b)
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
            pendingWake = true
            if (pageLoaded) dispatchWakeToPage()
        }
    }

    private fun dispatchWakeToPage() {
        if (!pendingWake || !pageLoaded || !::web.isInitialized) return
        pendingWake = false
        web.evaluateJavascript("window.onJarvisWake && window.onJarvisWake()", null)
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
            val resumeIntent = Intent(this, WakeWordService::class.java).setAction("RESUME_WAKE")
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(resumeIntent) else startService(resumeIntent)
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

    override fun onDestroy() {
        if (wakeReceiverRegistered) {
            unregisterReceiver(wakeReceiver)
            wakeReceiverRegistered = false
        }
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
