package com.example.wakeworddisplayimage

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible activity used as a lifecycle bridge between the background
 * wake-word service and Alexa. It lets us reliably know when Alexa's
 * voice activity has returned so the detector can restart.
 */
class AlexaBridgeActivity : Activity() {
    companion object {
        private const val REQUEST_ALEXA = 9001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchAlexa()
    }

    private fun launchAlexa() {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.amazon.dee.app",
                    "com.amazon.alexa.voice.VoiceHandsFreeSearchActivity"
                )
            }
            startActivityForResult(intent, REQUEST_ALEXA)
        } catch (_: Exception) {
            resumeDetectorAndFinish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ALEXA) resumeDetectorAndFinish()
    }

    override fun onResume() {
        super.onResume()
        // If Alexa could not be launched, this bridge becomes visible again.
        if (intent != null && !isFinishing && !isChangingConfigurations) {
            // Do not restart here; onActivityResult handles a normal Alexa return.
        }
    }

    private fun resumeDetectorAndFinish() {
        sendBroadcast(Intent(WakeWordService.ACTION_RESUME).setPackage(packageName))
        finish()
    }
}
