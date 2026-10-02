package com.mlbb.highlight.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mlbb.highlight.R

class FloatingControlService : Service() {
    private lateinit var windowManager: WindowManager
    private var expandedView: LinearLayout? = null
    private var attachedView: View? = null
    private var expanded = false
    private var startButton: TextView? = null
    private var stopButton: TextView? = null
    private var pauseButton: TextView? = null
    private var stateLabel: TextView? = null
    private var compactButton: TextView? = null
    private var isCapturing = ScreenCaptureService.isCapturing
    private var isPaused = ScreenCaptureService.isPaused
    private var isProjectionReady = ScreenCaptureService.isProjectionReady
    private var receiverRegistered = false

    private val captureStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_STATUS_CHANGED) return
            isCapturing = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_CAPTURING, false)
            isPaused = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_PAUSED, false)
            isProjectionReady = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_PREPARED, false)
            refreshButtons()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WindowManager::class.java)
        registerCaptureReceiver()
        if (Settings.canDrawOverlays(this)) {
            showControls()
        } else {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CONTROLS -> {
                startService(ScreenCaptureService.cancelPreparedIntent(this))
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(captureStateReceiver)
            receiverRegistered = false
        }
        attachedView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        attachedView = null
        expandedView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showControls() {
        if (expandedView != null) return
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = roundedBackground(COLOR_PANEL, COLOR_ACCENT, 20f)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleAndState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(6), dp(2))
            contentDescription = "Move floating recorder controls"
        }
        val title = TextView(this).apply {
            text = "MLBB RECORDER"
            setTextColor(Color.WHITE)
            textSize = 11f
            setLetterSpacing(0.08f)
        }
        val state = TextView(this).apply {
            textSize = 11f
            setPadding(0, dp(3), 0, 0)
        }
        titleAndState.addView(title)
        titleAndState.addView(state)
        titleAndState.setOnTouchListener(createDragListener(root))

        val collapseButton = actionButton("−", COLOR_BUTTON, "Collapse recorder controls") {
            setExpanded(false)
        }.apply {
            textSize = 21f
            setPadding(0, 0, 0, dp(3))
            contentDescription = "Collapse floating recorder controls"
        }
        header.addView(
            titleAndState,
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        header.addView(collapseButton, LinearLayout.LayoutParams(dp(40), dp(38)))
        root.addView(header)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val start = actionButton("Start", COLOR_START, "Start recording") {
            requestCapture()
        }
        val pause = actionButton("Pause", COLOR_BUTTON, "Pause or resume recording") {
            startService(ScreenCaptureService.pauseResumeIntent(this@FloatingControlService))
        }
        val stop = actionButton("Stop", COLOR_STOP, "Stop recording and save video") {
            startService(ScreenCaptureService.stopIntent(this@FloatingControlService))
        }
        actions.addView(start, actionLayoutParams(density))
        actions.addView(pause, actionLayoutParams(density))
        actions.addView(stop, actionLayoutParams(density))
        root.addView(actions)

        val compact = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBackground(COLOR_PANEL, COLOR_ACCENT, 28f)
            contentDescription = "Expand floating recorder controls"
        }
        compact.setOnTouchListener(createDragAndClickListener(compact) {
            setExpanded(true)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(160)
        }
        expandedView = root
        startButton = start
        pauseButton = pause
        stopButton = stop
        stateLabel = state
        compactButton = compact
        attachedView = compact
        windowManager.addView(compact, params.apply {
            width = dp(52)
            height = dp(52)
        })
        refreshButtons()
    }

    private fun setExpanded(value: Boolean) {
        val root = expandedView ?: return
        if (expanded == value) return
        val current = attachedView ?: return
        val next = if (value) root else compactButton ?: return
        val params = current.layoutParams as? WindowManager.LayoutParams ?: return
        windowManager.removeView(current)
        if (value) {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            params.width = dp(52)
            params.height = dp(52)
        }
        windowManager.addView(next, params)
        attachedView = next
        expanded = value
        if (!expanded) {
            compactButton?.text = if (!isCapturing) "REC" else if (isPaused) "Ⅱ" else "●"
            compactButton?.setTextColor(if (isCapturing && !isPaused) COLOR_RECORDING else Color.WHITE)
        }
    }

    private fun createDragListener(view: View): View.OnTouchListener =
        createDragAndClickListener(view) {}

    private fun createDragAndClickListener(
        view: View,
        onClick: () -> Unit
    ): View.OnTouchListener {
        var initialX = 0
        var initialY = 0
        var downX = 0f
        var downY = 0f
        var dragged = false
        val touchSlop = dp(8)
        return View.OnTouchListener { _, event ->
            val params = view.layoutParams as? WindowManager.LayoutParams
                ?: return@OnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop) {
                        dragged = true
                    }
                    if (dragged) {
                        params.x = initialX - deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun requestCapture() {
        if (isCapturing) return
        if (!isProjectionReady) {
            stateLabel?.text = "Prepare in recorder app first"
            stateLabel?.setTextColor(COLOR_PAUSED)
            return
        }
        startService(ScreenCaptureService.startPreparedIntent(this))
    }

    private fun refreshButtons() {
        startButton?.isEnabled = !isCapturing && isProjectionReady
        pauseButton?.isEnabled = isCapturing
        stopButton?.isEnabled = isCapturing
        startButton?.alpha = if (isCapturing) 0.45f else 1f
        pauseButton?.alpha = if (isCapturing) 1f else 0.45f
        stopButton?.alpha = if (isCapturing) 1f else 0.45f
        pauseButton?.text = if (isPaused) "Resume" else "Pause"
        stateLabel?.apply {
            text = when {
                !isCapturing && isProjectionReady -> "Ready to record"
                !isCapturing -> "Prepare in recorder app"
                isPaused -> "Recording paused"
                else -> "Recording"
            }
            setTextColor(
                when {
                    isCapturing && !isPaused -> COLOR_RECORDING
                    isPaused -> COLOR_PAUSED
                    else -> COLOR_MUTED
                }
            )
        }
        compactButton?.apply {
            text = if (!isCapturing) "REC" else if (isPaused) "Ⅱ" else "●"
            setTextColor(if (isCapturing && !isPaused) COLOR_RECORDING else Color.WHITE)
        }
    }

    private fun actionButton(
        label: String,
        color: Int,
        description: String,
        onClick: () -> Unit
    ) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(Color.WHITE)
        contentDescription = description
        background = roundedBackground(color, color, 12f)
        setOnClickListener { onClick() }
    }

    private fun actionLayoutParams(density: Float) = LinearLayout.LayoutParams(
        0,
        dp(42),
        1f
    ).apply {
        marginStart = (4 * density).toInt()
        marginEnd = (4 * density).toInt()
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius * resources.displayMetrics.density
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun registerCaptureReceiver() {
        val filter = IntentFilter(ScreenCaptureService.ACTION_STATUS_CHANGED)
        ContextCompat.registerReceiver(
            this,
            captureStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating recorder controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Start and Stop overlay available while playing"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("MLBB recorder controls are ready")
            .setContentText("Use the floating Start and Stop buttons while playing")
            .addAction(
                0,
                "Disable floating recorder",
                PendingIntent.getService(
                    this,
                    DISABLE_ACTION_REQUEST_CODE,
                    stopIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "floating_recorder_controls"
        private const val NOTIFICATION_ID = 1101
        private const val DISABLE_ACTION_REQUEST_CODE = 1102
        private const val ACTION_STOP_CONTROLS = "com.mlbb.highlight.overlay.STOP"
        private const val COLOR_PANEL = 0xF21B2334.toInt()
        private const val COLOR_ACCENT = 0xFF48D6C8.toInt()
        private const val COLOR_BUTTON = 0xFF35435B.toInt()
        private const val COLOR_START = 0xFF168A71.toInt()
        private const val COLOR_STOP = 0xFFAC4053.toInt()
        private const val COLOR_RECORDING = 0xFFFF6378.toInt()
        private const val COLOR_PAUSED = 0xFFFFC857.toInt()
        private const val COLOR_MUTED = 0xFFB7C2D4.toInt()

        fun stopIntent(context: Context): Intent =
            Intent(context, FloatingControlService::class.java).setAction(ACTION_STOP_CONTROLS)

        fun startIntent(context: Context): Intent =
            Intent(context, FloatingControlService::class.java)
    }
}
