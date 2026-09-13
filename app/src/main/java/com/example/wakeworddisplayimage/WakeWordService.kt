package com.example.wakeworddisplayimage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class WakeWordService : Service() {
    private var engine: OpenWakeWord? = null
    private var waitingForAlexa = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Alexa hands-free active")
            .setContentText("Listening for Alexa")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startDetector()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESUME -> {
                waitingForAlexa = false
                startDetector()
            }
            ACTION_STOP -> {
                stopDetector()
                stopSelf()
            }
            else -> if (!waitingForAlexa) startDetector()
        }
        return START_STICKY
    }

    private fun startDetector() {
        if (waitingForAlexa) return
        if (engine == null) {
            engine = OpenWakeWord(this, null) {
                handleWakeWord()
            }
        }
        engine?.startListeningForKeyword()
    }

    private fun handleWakeWord() {
        if (waitingForAlexa) return
        waitingForAlexa = true
        engine?.stopListening()
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.amazon.dee.app",
                    "com.amazon.alexa.voice.VoiceHandsFreeSearchActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("ALEXA", "Failed to launch Alexa from service", e)
            waitingForAlexa = false
            startDetector()
        }
    }

    private fun stopDetector() {
        engine?.release()
        engine = null
    }

    override fun onDestroy() {
        stopDetector()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alexa hands-free",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the wake-word detector active while the tablet is on the home screen."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_RESUME = "com.example.wakeworddisplayimage.RESUME"
        const val ACTION_STOP = "com.example.wakeworddisplayimage.STOP"
        private const val CHANNEL_ID = "alexa_hands_free"
        private const val NOTIFICATION_ID = 401
    }
}
