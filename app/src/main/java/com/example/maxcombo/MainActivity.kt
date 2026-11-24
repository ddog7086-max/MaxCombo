package com.example.maxcombo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.maxcombo.capture.PadService
import com.example.maxcombo.core.PDBoard
import com.example.maxcombo.core.PDRoute

class MainActivity : AppCompatActivity(), PadService.Listener {

    private lateinit var statusText: TextView
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
        toggleBtn = findViewById(R.id.toggleBtn)

        // Target picker: chosen here, persisted, read back by PadService on start.
        val sp = getSharedPreferences(PadService.PREFS, MODE_PRIVATE)
        val targetSpinner = findViewById<android.widget.Spinner>(R.id.targetSpinner)
        val labels = com.example.maxcombo.core.OrbPresets.all.map { it.label }
        val tAdapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels,
        )
        tAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        targetSpinner.adapter = tAdapter
        targetSpinner.setSelection(sp.getInt(PadService.KEY_TARGET, 0))
        targetSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    sp.edit().putInt(PadService.KEY_TARGET, position).apply()
                    PadService.instance?.currentTarget =
                        com.example.maxcombo.core.OrbPresets.all[position]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        // Effort picker: 轻松 3 / 标准 7 / 深入 12.
        val levelSpinner = findViewById<android.widget.Spinner>(R.id.levelSpinner)
        val lAdapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, listOf("轻松", "标准", "深入"),
        )
        lAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        levelSpinner.adapter = lAdapter
        levelSpinner.setSelection(sp.getInt(PadService.KEY_LEVEL, 1))
        levelSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    sp.edit().putInt(PadService.KEY_LEVEL, position).apply()
                    PadService.instance?.currentComplexity = intArrayOf(3, 7, 12)[position]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        // PuzzCombo-style solver settings: per-colour priority, quality, move
        // bias, and 全体攻撃 / コンボ優先 mode. Loaded from prefs, saved live.
        com.example.maxcombo.core.SolverSettings.load(this)
        val prioBars = listOf(
            findViewById<android.widget.SeekBar>(R.id.priorityFire),
            findViewById(R.id.priorityWater),
            findViewById(R.id.priorityWood),
            findViewById(R.id.priorityLight),
            findViewById(R.id.priorityDark),
            findViewById(R.id.priorityHeart),
        )
        prioBars.forEachIndexed { i, bar ->
            bar.progress = com.example.maxcombo.core.SolverSettings.colorPriority[i]
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    com.example.maxcombo.core.SolverSettings.colorPriority[i] = p
                    com.example.maxcombo.core.SolverSettings.save(this@MainActivity)
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        }
        val qualityBar = findViewById<android.widget.SeekBar>(R.id.qualityBar)
        qualityBar.progress = com.example.maxcombo.core.SolverSettings.quality
        qualityBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                com.example.maxcombo.core.SolverSettings.quality = p
                com.example.maxcombo.core.SolverSettings.save(this@MainActivity)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        val moveBar = findViewById<android.widget.SeekBar>(R.id.moveBar)
        moveBar.progress = com.example.maxcombo.core.SolverSettings.moveBias
        moveBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                com.example.maxcombo.core.SolverSettings.moveBias = p
                com.example.maxcombo.core.SolverSettings.save(this@MainActivity)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        val attackCheck = findViewById<android.widget.CheckBox>(R.id.attackCheck)
        val comboCheck = findViewById<android.widget.CheckBox>(R.id.comboCheck)
        attackCheck.isChecked = com.example.maxcombo.core.SolverSettings.attackMode
        comboCheck.isChecked = !com.example.maxcombo.core.SolverSettings.attackMode
        attackCheck.setOnCheckedChangeListener { _, c ->
            com.example.maxcombo.core.SolverSettings.attackMode = c
            comboCheck.isChecked = !c
            com.example.maxcombo.core.SolverSettings.save(this)
        }
        comboCheck.setOnCheckedChangeListener { _, c ->
            com.example.maxcombo.core.SolverSettings.attackMode = !c
            attackCheck.isChecked = !c
            com.example.maxcombo.core.SolverSettings.save(this)
        }

        // Route opacity slider: 80 (light) .. 255 (solid), persisted & live.
        val alphaBar = findViewById<android.widget.SeekBar>(R.id.alphaBar)
        alphaBar.progress = sp.getInt(PadService.KEY_ALPHA, 245) - 80
        alphaBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val a = p + 80
                sp.edit().putInt(PadService.KEY_ALPHA, a).apply()
                PadService.instance?.applyRouteAlpha(a)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

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
        val helpLink = findViewById<TextView>(R.id.btnHelp)
        helpLink.paintFlags = helpLink.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        helpLink.setOnClickListener { showHelp() }
    }

    private fun showHelp() {
        val text = """
            【用法】
            1. 打开 P&D 进到转珠回合。
            2. 点「开启助手」，允许通知与屏幕录制。
            3. 在本页选好目标模式与计算量；游戏内右上角点极小「分析」钮抓帧，照路线拖珠。

            【目标模式】
            · 最大 Combo：默认，追求最多 combo。
            · 火/水/木/光/闇 N 颗：第一波消够指定颗数。
            · 横排 / 十字：排成一整行 / 十字形。
            · 固定 5 色：火水木光闇各消一组。

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
        // instance present -> running; gone -> back to idle. The async start race is
        // covered by onStarted() firing once the overlay actually comes up.
        setRunning(PadService.instance != null)
    }

    override fun onPause() {
        super.onPause()
        // Do NOT clear the listener here: the user switches to the game and taps
        // the power button while this Activity is paused, and onStopped() must
        // still reach it. Only detach on destroy.
    }

    override fun onDestroy() {
        super.onDestroy()
        PadService.instance?.setListener(null)
    }

    override fun onBoard(board: PDBoard, route: PDRoute) {
        // Route is drawn by the in-game overlay; nothing to render on the main screen.
    }

    override fun onStarted() {
        setRunning(true)
    }

    override fun onStopped() {
        setRunning(false)
    }
}
