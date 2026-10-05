package com.sleepsentry.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.sleepsentry.R
import com.sleepsentry.capture.SentryService
import com.sleepsentry.store.NightStore
import com.sleepsentry.util.Prefs

/**
 * 主界面宿主：底部导航 + 四个页面。
 *
 * 之前所有内容堆在一个 ScrollView 里，页面长得看不到底 —— 用户反馈过这个问题。
 * 拆成 状态 / 报告 / 日历 / 设置 四页，各自只放自己该有的东西。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    lateinit var store: NightStore
        private set

    private val fragments = linkedMapOf<Int, Fragment>()
    private var activeId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configureSystemBars()
        prefs = Prefs(this)
        store = NightStore(this)

        val nav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        fragments[R.id.nav_status] = StatusFragment()
        fragments[R.id.nav_report] = ReportFragment()
        fragments[R.id.nav_calendar] = CalendarFragment()
        fragments[R.id.nav_settings] = SettingsFragment()

        nav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        // 恢复上次选中的页
        val start = savedInstanceState?.getInt(KEY_TAB, R.id.nav_status) ?: R.id.nav_status
        nav.selectedItemId = start
    }

    /** Keep every page below the Android status bar and lift the tabs above gesture navigation. */
    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val root = findViewById<View>(R.id.mainRoot)
        val nav = findViewById<View>(R.id.bottomNav)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, 0)
            nav.setPadding(nav.paddingLeft, nav.paddingTop, nav.paddingRight, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, activeId)
    }

    fun showTab(id: Int) {
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
            .selectedItemId = id
    }

    private fun show(id: Int) {
        if (id == activeId) return
        val tx = supportFragmentManager.beginTransaction()
        fragments[activeId]?.let { if (it.isAdded) tx.hide(it) }
        val f = fragments[id] ?: return
        if (!f.isAdded) tx.add(R.id.container, f, id.toString()) else tx.show(f)
        tx.commitNowAllowingStateLoss()
        activeId = id
    }

    override fun onResume() {
        super.onResume()
        if (prefs.enabled) ensurePermissions()
    }

    // ------------------------------------------------------------ 权限与开关

    fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun ensurePermissions() {
        val need = ArrayList<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_PERM) {
            toast(
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) "已授权，可以开始记录"
                else "没有麦克风权限，无法记录"
            )
            fragments.values.forEach { if (it is StatusFragment) it.render() }
        }
    }

    /** 开启/停止守夜。放在宿主里，页面只管调。 */
    fun toggleSentry() {
        if (prefs.enabled) {
            prefs.enabled = false
            SentryService.stop(this)
            toast("已停止")
        } else {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                ensurePermissions()
                return
            }
            prefs.enabled = true
            prefs.lastFailureReason = ""
            com.sleepsentry.capture.MorningNotifier.schedule(this, prefs.morningNotifyMin)
            SentryService.start(this)
            offerBatteryExempt()
            toast("已开启。插上充电器、把手机放床头就行")
        }
    }

    /**
     * 国厂 ROM 的后台限制是本项目头号风险，首次开启就主动引导关掉。
     * 不关的话录不到一小时就被系统杀掉。
     */
    private fun offerBatteryExempt() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_TAB = "tab"
        const val REQ_PERM = 100
    }
}

/** 报告页要显示哪一晚（日历页点选后传过来） */
object ReportSelection {
    @Volatile
    var date: String? = null
}
