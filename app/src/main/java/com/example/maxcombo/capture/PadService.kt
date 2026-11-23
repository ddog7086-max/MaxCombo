package com.example.maxcombo.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import android.app.Service
import com.example.maxcombo.R
import com.example.maxcombo.core.Orb
import com.example.maxcombo.core.OrbRecognizer
import com.example.maxcombo.core.PDBoard
import com.example.maxcombo.core.PDBoardRect
import com.example.maxcombo.core.PDRoute
import com.example.maxcombo.core.PDSolver
import com.example.maxcombo.overlay.RouteBoardView
import com.example.maxcombo.overlay.ColorKeyView
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * One foreground service does everything the iOS version needed two processes
 * for: MediaProjection grabs frames, the recognizer reads the 6x5 board, the SA
 * solver computes a route, and a floating overlay draws it above the game.
 */
class PadService : Service() {

    interface Listener {
        fun onBoard(board: PDBoard, route: PDRoute)
    }

    @Volatile
    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    private lateinit var windowManager: WindowManager
    private var overlay: RouteBoardView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var rootView: FrameLayout? = null
    private var controlBar: FrameLayout? = null
    private var colorKey: ColorKeyView? = null
    private var colorKeyBar: FrameLayout? = null
    private var analyzeBtn: android.widget.Button? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var solverExecutor = Executors.newSingleThreadExecutor()
    private var lastCells: IntArray? = null
    private var lockedCells: IntArray? = null
    private var lockedRoute: PDRoute? = null
    private var forceResolve = false
    private var lastAnalysis = 0L

    private val analyzeTask = object : Runnable {
        override fun run() {
            analyze()
            mainHandler.postDelayed(this, ANALYZE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Drop any leftover overlay/state from a previous run so restarting does
        // not stack a second board on top of the old still-visible one.
        resetBeforeStart()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), FOREGROUND_TYPE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, buildNotification())
        }

        if (resultCode != 0 && data != null && projection == null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    teardown()
                }
            }, mainHandler)
            startCapture()
            addOverlay()
            // Static frame mode: no continuous polling. The user taps 分析 to
            // grab one frame, recognise it and draw the route once.
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics: DisplayMetrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "maxcombo-capture",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null,
        )
    }

    /**
     * Static frame mode: grab ONE latest frame, recognise it, solve and draw the
     * route once. No continuous polling, no lock loop. The fixed FALLBACK box
     * covers the whole bottom board so no manual alignment is needed.
     */
    private fun restoreAnalyzeBtn() { analyzeBtn?.text = "分析" }

    private fun analyze() {
        analyzeBtn?.text = "分析中…"
        val reader = imageReader ?: run { restoreAnalyzeBtn(); return }
        val image = reader.acquireLatestImage() ?: run { restoreAnalyzeBtn(); return }

        val bitmap = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(buffer)
            val scale = min(1.0, 900.0 / padded.width)
            if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    padded, (padded.width * scale).toInt(),
                    (padded.height * scale).toInt(), true,
                ).also { if (it != padded) padded.recycle() }
            } else padded
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        } ?: run { restoreAnalyzeBtn(); return }

        // Always use the locked 6x5 grid measured from a real screenshot;
        // no manual calibration. Recognition only reads inside this board.
        val rect = PDBoardRect.LOCKED
        overlay?.boardRect = rect

        val board = OrbRecognizer.classify(bitmap, rect)
        bitmap.recycle()
        if (board == null) { restoreAnalyzeBtn(); return }

        // Guard: not enough recognised orbs means the frame does not show a full board.
        val filled = board.cells.count { it != Orb.EMPTY }
        if (filled < 24) { restoreAnalyzeBtn(); return }

        colorKey?.setBoard(board)

        solverExecutor.execute {
            // Complexity slider removed: search effort and route length are fixed so
            // the solver always targets a stable 5..7-combo result.
            val route = PDSolver.solve(board, maxLen = 22, complexity = 7)
            mainHandler.post {
                overlay?.show(board, route)
                listener?.onBoard(board, route)
                restoreAnalyzeBtn()
            }
        }
    }

    // ------------------------------------------------------------- overlay

    private fun addOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // --- Full-screen transparent route layer, touch-through so orbs stay
        // draggable. The grid maps onto the fixed FALLBACK full-board rectangle.
        val routeRoot = FrameLayout(this)
        val view = RouteBoardView(this)
        view.showOrbs = false
        view.routeAlpha = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ALPHA, 245)
        routeRoot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(routeRoot, params)

        // --- Small control window (touchable), top-right: 对准 aligns the box,
        // 分析 grabs one frame; the slider sets route opacity.
        val bar = FrameLayout(this)
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val panelKd = resources.displayMetrics.density
        val panelW = (210 * panelKd).toInt()
        val matchW = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        // Version tag so the user can confirm the installed build at a glance.
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "?" }
        col.addView(android.widget.TextView(this).apply {
            text = "Max Combo v$ver"
            textSize = 10f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(8, 2, 8, 2)
        }, matchW)
        val analyzeBtn = Button(this).apply {
            text = "分析"
            textSize = 14f
            setPadding(28, 8, 28,  8)
            setOnClickListener { analyze() }
        }
        this@PadService.analyzeBtn = analyzeBtn
        col.addView(analyzeBtn, matchW)

        // Close button: tears down the whole assistant (removes every overlay,
        // stops the foreground service).
        val closeBtn = Button(this).apply {
            text = "关闭"
            textSize = 14f
            setPadding(28, 8, 28, 8)
            setOnClickListener { teardown() }
        }
        col.addView(closeBtn, matchW)

        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun label(text: String): android.widget.TextView = android.widget.TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(8, 4, 8, 4)
        }

        // Opacity slider: 80 (light) .. 255 (solid). Persisted and applied live.
        col.addView(label("透明度"), matchW)
        val alphaBar = android.widget.SeekBar(this).apply {
            max = 175
            progress = sp.getInt(KEY_ALPHA, 245) - 80
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val a = p + 80
                    view.routeAlpha = a
                    view.invalidate()
                    sp.edit().putInt(KEY_ALPHA, a).apply()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        }
        col.addView(alphaBar, matchW)

        bar.addView(col, FrameLayout.LayoutParams(
            panelW,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        val barParams = WindowManager.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        barParams.gravity = Gravity.TOP or Gravity.END
        barParams.x = 8
        barParams.y = 40
        windowManager.addView(bar, barParams)

        // --- Standalone colour key window, fixed at the screen's top-left corner,
        // touch-through: never sits inside the route box or covers an orb.
        val keyView = ColorKeyView(this)
        val keyBar = FrameLayout(this)
        val kd = resources.displayMetrics.density
        keyBar.addView(
            keyView,
            FrameLayout.LayoutParams((162 * kd).toInt(), (66 * kd).toInt()),
        )
        val keyParams = WindowManager.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        keyParams.gravity = Gravity.TOP or Gravity.START
        keyParams.x = 8
        keyParams.y = 70
        windowManager.addView(keyBar, keyParams)

        overlay = view
        overlayParams = params
        rootView = routeRoot
        controlBar = bar
        colorKey = keyView
        colorKeyBar = keyBar
    }

    private fun buildNotification(): Notification {
        val channelId = "maxcombo"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Max Combo", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Max Combo 运行中")
            .setContentText("正在识别盘面并绘制路线")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    /** Remove any previous overlay/state before a fresh start. */
    private fun resetBeforeStart() {
        mainHandler.removeCallbacks(analyzeTask)
        lockedCells = null
        lockedRoute = null
        detachOverlays()
        solverExecutor.shutdownNow()
        solverExecutor = Executors.newSingleThreadExecutor()
    }

    /** Remove the real root views added to WindowManager (not the inner RouteBoardView). */
    private fun detachOverlays() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        controlBar?.let { runCatching { windowManager.removeView(it) } }
        colorKeyBar?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        controlBar = null
        colorKeyBar = null
        colorKey = null
        overlay = null
    }

    private fun teardown() {
        mainHandler.removeCallbacks(analyzeTask)
        lockedCells = null
        lockedRoute = null
        solverExecutor.shutdownNow()
        detachOverlays()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        teardown()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PadService? = null
            private set

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 42
        private const val ANALYZE_INTERVAL_MS = 900L

        /** Cells allowed to differ before we treat the board as changed. */
        private const val STABLE_CELL_TOLERANCE = 3
        private const val FOREGROUND_TYPE =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        const val PREFS = "maxcombo_calibration"
        const val KEY_ALPHA = "route_alpha"
    }
}
