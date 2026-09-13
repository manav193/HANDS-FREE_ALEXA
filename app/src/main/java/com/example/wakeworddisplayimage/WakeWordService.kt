package com.example.wakeworddisplayimage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class WakeWordService : Service() {
    private var engine: OpenWakeWord? = null
    private var waitingForAlexa = false
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotificationPercent = -1

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startDetector()
        else broadcastStatus(0f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESUME -> { waitingForAlexa = false; startDetector() }
            ACTION_STOP -> { stopDetector(); stopSelf() }
            else -> if (!waitingForAlexa) startDetector()
        }
        return START_STICKY
    }

    private fun startDetector() {
        if (waitingForAlexa) return
        if (engine == null) {
            engine = OpenWakeWord(this, null, { handleWakeWord() }, { score -> broadcastStatus(score) })
        }
        engine?.startListeningForKeyword()
    }

    private fun handleWakeWord() {
        if (waitingForAlexa) return
        waitingForAlexa = true
        broadcastWakeWord()
        engine?.stopListening()

        // Do NOT open our own Activity. Android 10 permits this direct background
        // launch when SYSTEM_ALERT_WINDOW is granted. The detector is restarted
        // after Alexa returns/finishes, without briefly showing our dashboard.
        mainHandler.postDelayed({
            try {
                val intent = Intent().apply {
                    component = android.content.ComponentName(
                        "com.amazon.dee.app",
                        "com.amazon.alexa.voice.VoiceHandsFreeSearchActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch Alexa", e)
                waitingForAlexa = false
                startDetector()
            }
        }, 300L)

        // VoiceHandsFreeSearchActivity normally ends after handling the request.
        // Re-arm the detector after a short safety window so the next wake word
        // does not get lost even when Alexa returns without notifying us.
        mainHandler.postDelayed({
            waitingForAlexa = false
            startDetector()
        }, 7000L)
    }

    private fun broadcastStatus(score: Float) {
        val safeScore = score.coerceIn(0f, 1f)
        val percent = (safeScore * 100f).toInt()
        if (percent != lastNotificationPercent) {
            lastNotificationPercent = percent
            notificationManager.notify(NOTIFICATION_ID, buildNotification(percent))
        }
        sendBroadcast(Intent(ACTION_SCORE).setPackage(packageName).putExtra(EXTRA_SCORE, safeScore))
    }

    private fun buildNotification(percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Alexa hands-free active")
            .setContentText("Wake-word confidence: $percent% • trigger: 50%")
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun broadcastWakeWord() { sendBroadcast(Intent(ACTION_COUNT).setPackage(packageName)) }

    private fun stopDetector() {
        mainHandler.removeCallbacksAndMessages(null)
        engine?.release()
        engine = null
    }

    override fun onDestroy() { stopDetector(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Alexa hands-free", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the wake-word detector active while the tablet is on the home screen." })
        }
    }

    companion object {
        const val ACTION_RESUME = "com.example.wakeworddisplayimage.RESUME"
        const val ACTION_STOP = "com.example.wakeworddisplayimage.STOP"
        const val ACTION_SCORE = "com.example.wakeworddisplayimage.SCORE"
        const val ACTION_COUNT = "com.example.wakeworddisplayimage.COUNT"
        const val EXTRA_SCORE = "score"
        private const val CHANNEL_ID = "alexa_hands_free"
        private const val NOTIFICATION_ID = 401
        private const val TAG = "WakeWordService"
    }
}
