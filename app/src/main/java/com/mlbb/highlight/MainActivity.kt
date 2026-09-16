package com.mlbb.highlight

import android.Manifest
import android.os.Bundle
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mlbb.highlight.recording.ScreenCaptureService
import com.mlbb.highlight.storage.HighlightRepository
import com.mlbb.highlight.ui.HighlightsScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val highlightRepository = remember { HighlightRepository(applicationContext) }
                    var isCapturing by rememberSaveable { mutableStateOf(ScreenCaptureService.isCapturing) }
                    var recordingSeconds by rememberSaveable { mutableStateOf(0) }
                    var statusMessage by rememberSaveable {
                        mutableStateOf(
                            if (ScreenCaptureService.isCapturing) {
                                "Capture running"
                            } else {
                                "Capture stopped"
                            }
                        )
                    }
                    var shouldStartAfterNotificationPermission by rememberSaveable {
                        mutableStateOf(false)
                    }
                    var highlights by remember { mutableStateOf(highlightRepository.listHighlights()) }

                    fun refreshCaptureUiState() {
                        isCapturing = ScreenCaptureService.isCapturing
                        statusMessage = if (isCapturing) "Capture running" else "Capture stopped"
                    }

                    val projectionManager = remember {
                        getSystemService(MediaProjectionManager::class.java)
                    }

                    val projectionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                            ContextCompat.startForegroundService(
                                this,
                                ScreenCaptureService.startIntent(
                                    this,
                                    result.resultCode,
                                    result.data ?: Intent()
                                )
                            )
                            isCapturing = true
                            statusMessage = "Capture running"
                            recordingSeconds = 0
                        } else {
                            isCapturing = false
                            recordingSeconds = 0
                            statusMessage = "Capture permission denied"
                        }
                    }

                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) {
                        if (shouldStartAfterNotificationPermission) {
                            shouldStartAfterNotificationPermission = false
                            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    }

                    LaunchedEffect(Unit) {
                        refreshCaptureUiState()
                    }

                    LaunchedEffect(isCapturing) {
                        while (isCapturing) {
                            delay(1_000L)
                            recordingSeconds += 1
                        }
                    }

                    DisposableEffect(Unit) {
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                when (intent?.action) {
                                    ScreenCaptureService.ACTION_STATUS_CHANGED -> {
                                        val wasCapturing = isCapturing
                                        isCapturing = intent.getBooleanExtra(
                                            ScreenCaptureService.EXTRA_IS_CAPTURING,
                                            false
                                        )
                                        if (isCapturing && !wasCapturing) {
                                            recordingSeconds = 0
                                        }
                                        if (!isCapturing) {
                                            ScreenCaptureService.setCapturing(false)
                                            recordingSeconds = 0
                                        }
                                        statusMessage = if (isCapturing) "Capture running" else "Capture stopped"
                                    }

                                    ScreenCaptureService.ACTION_HIGHLIGHT_STATUS_CHANGED -> {
                                        statusMessage = intent.getStringExtra(
                                            ScreenCaptureService.EXTRA_HIGHLIGHT_MESSAGE
                                        ) ?: statusMessage
                                        highlights = highlightRepository.listHighlights()
                                    }
                                }
                            }
                        }

                        val filter = IntentFilter().apply {
                            addAction(ScreenCaptureService.ACTION_STATUS_CHANGED)
                            addAction(ScreenCaptureService.ACTION_HIGHLIGHT_STATUS_CHANGED)
                        }

                        ContextCompat.registerReceiver(
                            this@MainActivity,
                            receiver,
                            filter,
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )

                        onDispose {
                            unregisterReceiver(receiver)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("MLBB Highlight", style = MaterialTheme.typography.headlineMedium)
                        Text("Screen Capture MVP")
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("Status: $statusMessage")
                        if (isCapturing) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = formatElapsed(recordingSeconds),
                                style = MaterialTheme.typography.displayLarge
                            )
                            Text("Recording elapsed")
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            enabled = !isCapturing,
                            onClick = {
                                if (shouldRequestNotificationPermission()) {
                                    shouldStartAfterNotificationPermission = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                                }
                            }
                        ) {
                            Text("Start Capture")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            enabled = isCapturing,
                            onClick = {
                                ScreenCaptureService.setCapturing(false)
                                startService(ScreenCaptureService.stopIntent(this@MainActivity))
                                isCapturing = false
                                recordingSeconds = 0
                                statusMessage = "Capture stopped"
                            }
                        ) {
                            Text("Stop Capture")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            enabled = isCapturing,
                            onClick = {
                                startService(ScreenCaptureService.manualHighlightIntent(this@MainActivity))
                                statusMessage = "Highlight requested"
                            }
                        ) {
                            Text("Save Highlight")
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HighlightsScreen(
                            highlights = highlights,
                            onPlay = { entity ->
                                val file = java.io.File(entity.filePath)
                                if (!file.exists() || !file.isFile || file.length() <= 0L) {
                                    statusMessage = "Highlight file is missing or empty"
                                    return@HighlightsScreen
                                }
                                val uri = FileProvider.getUriForFile(
                                    this@MainActivity,
                                    "$packageName.fileprovider",
                                    file
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(intent)
                            },
                            onDelete = { entity ->
                                highlightRepository.deleteHighlight(entity)
                                highlights = highlightRepository.listHighlights()
                            },
                            onShare = { entity ->
                                val uri = FileProvider.getUriForFile(
                                    this@MainActivity,
                                    "$packageName.fileprovider",
                                    java.io.File(entity.filePath)
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, entity.title)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(shareIntent, "Share highlight"))
                            }
                        )
                    }
                }
            }
        }
    }

    private fun shouldRequestNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun formatElapsed(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "%02d:%02d".format(minutes, remainingSeconds)
    }
}
