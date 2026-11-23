package com.example.maxcombo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.maxcombo.capture.PadService
import com.example.maxcombo.core.PDBoard
import com.example.maxcombo.core.PDRoute
import com.example.maxcombo.overlay.RouteBoardView

class MainActivity : AppCompatActivity(), PadService.Listener {

    private lateinit var statusText: TextView
    private lateinit var preview: RouteBoardView
    private lateinit var toggleBtn: Button
    private var versionName = ""

    // Android 13+ requires a runtime notification permission; without it the
    // foreground capture service cannot start and screen capture fails with a
    // permission error on newer devices.
    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startHelper()
        } else {
            Toast.makeText(this, "未授予通知权限，录屏服务可能无法启动", Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, PadService::class.java).apply {
                putExtra(PadService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PadService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            setRunning(true)
        } else {
            Toast.makeText(this, "未授权屏幕录制，助手未启动", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        preview = findViewById(R.id.preview)
        preview.showOrbs = true // main preview re-draws the board; overlay draws route only
        preview.onCellClick = { idx ->
            PadService.instance?.fixedStart = idx
            Toast.makeText(
                this,
                "起点已设：第${PDBoard.rowOf(idx) + 1}行 第${PDBoard.colOf(idx) + 1}列",
                Toast.LENGTH_SHORT,
            ).show()
        }
        toggleBtn = findViewById(R.id.toggleBtn)

        versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        toggleBtn.setOnClickListener {
            if (PadService.instance != null) {
                stopService(Intent(this, PadService::class.java))
                setRunning(false)
            } else {
                ensureNotificationPermissionThenStart()
            }
        }
        findViewById<Button>(R.id.btnHelp).setOnClickListener { showHelp() }
    }

    private fun showHelp() {
        val text = """
            【用法】
            1. 打开 P&D 进到转珠回合。
            2. 点「开启助手」，允许通知与屏幕录制。
            3. 悬浮栏选目标、计算量，点「分析」抓帧，照路线拖珠。

            【目标模式】
            · 最大 Combo：默认，追求最多 combo。
            · 火/水/木/光/闇 N 颗：第一波消够指定颗数。
            · 横排 / 十字：排成一整行 / 十字形。
            · 固定 5 色：火水木光闇各消一组。

            【手设起点】
            在主界面预览图上点想开始的那一格，路线就从该格起；
            悬浮栏「清除起点」恢复自动。

            【FAQ】
            · 识别错色：换主题/机型后颜色可能要重调，尽量在明亮盘面分析。
            · 实际 combo 比显示多：路线只算这一步能确定的，不含天空掉落。
            · 权限报错：先允许通知权限，再给悬浮窗与录屏权限。
            · 这是外部练习工具，不读游戏内存、不注入，账号风险自担。
        """.trimIndent()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("说明 / FAQ")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startHelper()
    }

    private fun startHelper() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun setRunning(running: Boolean) {
        val ver = if (versionName.isNotEmpty()) "v$versionName  " else ""
        toggleBtn.text = if (running) "停止助手" else "开启助手"
        statusText.text = if (running) {
            "${ver}运行中：点「分析」抓帧识别盘面，再照着路线拖珠。"
        } else {
            "${ver}未开启。先授予通知与悬浮窗权限，再点「开启助手」允许屏幕录制。"
        }
    }

    override fun onResume() {
        super.onResume()
        PadService.instance?.setListener(this)
        if (PadService.instance != null) setRunning(true)
    }

    override fun onPause() {
        super.onPause()
        PadService.instance?.setListener(null)
    }

    override fun onBoard(board: PDBoard, route: PDRoute) {
        preview.show(board, route)
    }
}
