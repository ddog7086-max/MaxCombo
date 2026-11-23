package com.example.maxcombo

import android.content.Intent
import android.net.Uri
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
                startHelper()
            }
        }
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
            "${ver}运行中：点「对准」拖框包住珠子，再退出即可拖珠。"
        } else {
            "${ver}未开启。先授予悬浮窗权限，再点「开启助手」允许屏幕录制。"
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
