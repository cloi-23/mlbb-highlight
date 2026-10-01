package com.mlbb.highlight.recording

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

class VoiceCommandActivity : ComponentActivity() {
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val command = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        if (command != null) {
            startService(ScreenCaptureService.voiceCommandIntent(this, command))
        } else {
            reportError("Voice command cancelled")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            reportError("No speech recognition service is available")
            finish()
            return
        }

        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say Pause, Resume, or Stop")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        if (recognitionIntent.resolveActivity(packageManager) == null) {
            reportError("No speech recognition app is available")
            finish()
            return
        }
        try {
            speechLauncher.launch(recognitionIntent)
        } catch (error: ActivityNotFoundException) {
            reportError("Could not start speech recognition")
            finish()
        }
    }

    private fun reportError(message: String) {
        sendBroadcast(
            Intent(ScreenCaptureService.ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(ScreenCaptureService.EXTRA_ERROR_MESSAGE, message)
        )
    }
}
