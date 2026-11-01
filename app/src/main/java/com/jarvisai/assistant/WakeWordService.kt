package com.jarvisai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class WakeWordService : Service(), RecognitionListener {
    private var model: Model? = null
    private var speech: SpeechService? = null
    @Volatile private var triggered = false
    private val channelId = "jarvis_wake_word"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Jarvis wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(41, buildNotification("Jarvis ব্যাকগ্রাউন্ডে শুনছে"))
        thread {
            try {
                val dir = File(filesDir, "vosk-model-small-en-us-0.15")
                if (!File(dir, "conf").exists()) downloadModel(dir)
                LibVosk.setLogLevel(LogLevel.WARN)
                model = Model(dir.absolutePath)
                val recognizer = Recognizer(model, 16000.0f, "[\"jarvis\", \"[unk]\"]")
                speech = SpeechService(recognizer, 16000.0f)
                speech?.startListening(this)
            } catch (e: Exception) {
                updateNotification("Wake word চালু হয়নি: ${e.message ?: "মডেল লোড সমস্যা"}")
                stopSelf()
            }
        }
    }

    private fun downloadModel(target: File) {
        val zip = File(cacheDir, "vosk-model.zip")
        URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
            .openConnection().apply { connectTimeout = 20000; readTimeout = 120000 }
            .getInputStream().use { input -> zip.outputStream().use { output -> input.copyTo(output) } }
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val relative = entry.name.substringAfter('/', "")
                if (relative.isNotEmpty()) {
                    val out = File(target, relative)
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zip.delete()
    }

    private fun detect(raw: String) {
        if (triggered) return
        val heard = try { JSONObject(raw).optString("text").lowercase() } catch (_: Exception) { raw.lowercase() }
        if (heard.contains("jarvis")) {
            triggered = true
            speech?.stop()
            updateNotification("Jarvis জেগে উঠেছে — কথা বলতে নোটিফিকেশনে চাপ দিন")
        }
    }
    override fun onPartialResult(hypothesis: String) = detect(hypothesis)
    override fun onResult(hypothesis: String) = detect(hypothesis)
    override fun onFinalResult(hypothesis: String) = detect(hypothesis)
    override fun onError(e: Exception) { updateNotification("মাইক্রোফোন সমস্যা: ${e.message ?: "অজানা"}") }
    override fun onTimeout() {}

    private fun buildNotification(message: String): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("JARVIS_WAKE", triggered)
        }
        val pending = PendingIntent.getActivity(this, 42, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS AI").setContentText(message)
            .setContentIntent(pending).setOngoing(true).build()
    }
    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(41, buildNotification(message))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() {
        speech?.stop()
        speech?.shutdown()
        model?.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
