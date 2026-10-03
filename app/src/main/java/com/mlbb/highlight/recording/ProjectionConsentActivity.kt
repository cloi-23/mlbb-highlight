package com.mlbb.highlight.recording

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mlbb.highlight.settings.AppSettings
import com.mlbb.highlight.settings.RecordingAudioSource
import com.mlbb.highlight.settings.SettingsRepository

class ProjectionConsentActivity : ComponentActivity() {
    private val settings by lazy { SettingsRepository(applicationContext).load() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestAudioPermissionOrProjection()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchProjectionConsent()
        } else {
            finishWithError("Microphone permission is needed for the selected audio source")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            finishWithError("Screen-capture permission was not granted")
            return@registerForActivityResult
        }
        try {
            ContextCompat.startForegroundService(
                this,
                ScreenCaptureService.startIntent(this, result.resultCode, data, settings)
            )
            finish()
        } catch (error: SecurityException) {
            finishWithError("Android blocked the recording service from starting")
        } catch (error: IllegalStateException) {
            finishWithError(error.localizedMessage ?: "The recording service could not start")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (settings.saveLocationUri == null) {
            finishWithError("Choose a save folder in the recorder app before recording")
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestAudioPermissionOrProjection()
        }
    }

    private fun requestAudioPermissionOrProjection() {
        if (
            settings.audioSource != RecordingAudioSource.SILENT &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Toast.makeText(
                this,
                "Select MLBB in Android's capture prompt to exclude other apps.",
                Toast.LENGTH_LONG
            ).show()
        }
        projectionLauncher.launch(intent)
    }

    private fun finishWithError(message: String) {
        sendBroadcast(
            Intent(ScreenCaptureService.ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(ScreenCaptureService.EXTRA_ERROR_MESSAGE, message)
        )
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
