package com.wentao.kacha.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wentao.kacha.BuildConfig
import com.wentao.kacha.KachaApp
import com.wentao.kacha.R
import com.wentao.kacha.databinding.ActivityMainBinding
import com.wentao.kacha.databinding.ItemPickAppBinding
import com.wentao.kacha.databinding.ItemTargetAppBinding
import com.wentao.kacha.service.FloatBallService
import com.wentao.kacha.service.ShotAccessibilityService
import com.wentao.kacha.util.AppFinder
import com.wentao.kacha.util.Prefs

/**
 * ============================================================================
 * 首页 = 唯一的设置页
 *
 * ── 这个界面只有四个区块 ──
 *   ① 状态   悬浮窗 / 截屏权限 / 悬浮球   —— 三个开关，缺哪个点哪个
 *   ② 发给谁  已添加的目标 App 列表 + 添加按钮
 *   ③ 动作   存相册 / 放剪贴板 / 自动送过去
 *   ④ 用法   怎么用 + 几个要提前知道的事
 *
 * ── 为什么没有「测试截图」按钮 ──
 *   球就在屏幕上，直接点球就是最真实的测试。多做一个按钮反而让界面变重。
 *
 * ⚠️ 状态刷新放在 onResume，不放 onCreate：
 *    用户「去开权限」→ 跳系统设置 → 回来，只有 onResume 会再走一遍。
 *    放 onCreate 的话回来还是显示「没开」，用户会以为没生效又去点一次。
 * ============================================================================
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KachaShot"

        /** 从通知点进来时带这个 extra，直接落到无障碍设置页 */
        const val EXTRA_GOTO_ACCESSIBILITY = "goto_accessibility"
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    /**
     * Android 13+ 的通知权限请求。
     * 不给也能用（前台服务照样跑），只是用户看不到那条常驻通知 ——
     * 所以拒绝了也不拦，不弹二次说服（用户嫌烦）。
     */
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 给不给都行，什么都不做 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        setupStatusSection()
        setupTargetSection()
        setupSwitchSection()
        setupVersion()

        // Android 13+ 首次进来顺手问一下通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // ★ 补拉悬浮球。
        //   用户「开球的意图」还在（ballEnabled=true）但服务没跑 —— 最常见的原因是
        //   去系统设置开无障碍那段时间里，App 退到后台被 ROM 回收了进程：
        //   服务没了、球也没了，可用户以为自己早就开好了。
        //   不补拉的话，用户回来只看到「球不见了 + 状态显示没启动」，以为坏了。
        if (prefs.ballEnabled && !FloatBallService.isRunning) {
            FloatBallService.requestStart(this)
        }

        // ★ 状态一定要在这里刷 —— 用户从系统设置页回来只有 onResume 会走
        refreshStatus()
        refreshTargets()
        refreshSwitches()

        // 服务是异步起来的（startForegroundService → onCreate 要走一整套），
        // 立刻刷还会是「没启动」，稍后再刷一次才准。
        b.root.postDelayed({ runCatching { refreshStatus() } }, 500)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_GOTO_ACCESSIBILITY, false) == true) {
            // 用完就清，免得下次进来又跳一次
            intent.removeExtra(EXTRA_GOTO_ACCESSIBILITY)
            openAccessibilitySettings()
        }
    }

    // ==================== ① 状态区 ====================

    private fun setupStatusSection() {
        // 悬浮窗权限
        b.btnOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                toast(getString(R.string.status_overlay_on))
            } else {
                openOverlaySettings()
            }
        }

        // 截屏权限（无障碍）
        b.btnAcc.setOnClickListener { openAccessibilitySettings() }

        // 启动 / 关闭悬浮球
        b.btnBallToggle.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openOverlaySettings()
                return@setOnClickListener
            }
            if (FloatBallService.isRunning) {
                prefs.ballEnabled = false
                FloatBallService.requestStop(this)
                toast("悬浮球已关闭")
            } else {
                prefs.ballEnabled = true
                FloatBallService.requestStart(this)
                toast("悬浮球已启动，去别的 App 上试试")
            }
            // 状态行要稍等一下才准（服务是异步起来的）
            b.root.postDelayed({ refreshStatus() }, 400)
        }
    }

    /**
     * 刷新三行状态。
     *
     * ⚠️ 图标切换要显式换 src —— 别指望 drawable 自己变色，
     *    我们用的是两张完全不同的图（ic_check 绿勾 / ic_warn 黄感叹号）。
     *
     * ⚠️⚠️ 「截屏权限」为什么判 isEnabledInSystem 而**不是** isAlive（v1.0 用户反馈的真凶）：
     *    isAlive 的第一行是 `instance ?: return false` —— 服务实例还没连上时直接返回 false，
     *    **压根没去问系统**。而用户开完无障碍切回 App 那一刻，系统 bind 服务是异步的，
     *    instance 经常还是 null → 首页就显示「没开」，可系统里明明已经勾上了。
     *    另外 isAlive 的语义是「现在能不能立刻截图」（内部用的），
     *    跟界面上要表达的「这步权限你做了没有」不是一回事。
     *    用户能控制的只有「系统里勾没勾」，所以这里就照实显示这个。
     */
    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        b.ivOverlayState.setImageResource(if (overlayOk) R.drawable.ic_check else R.drawable.ic_warn)
        b.tvOverlayState.text = getString(if (overlayOk) R.string.status_overlay_on else R.string.status_overlay_off)
        b.btnOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE

        val accOk = ShotAccessibilityService.isEnabledInSystem(this)
        b.ivAccState.setImageResource(if (accOk) R.drawable.ic_check else R.drawable.ic_warn)
        b.tvAccState.text = getString(if (accOk) R.string.status_acc_on else R.string.status_acc_off)
        b.btnAcc.visibility = if (accOk) View.GONE else View.VISIBLE

        val ballOk = FloatBallService.isRunning
        b.ivBallState.setImageResource(if (ballOk) R.drawable.ic_check else R.drawable.ic_warn)
        b.tvBallState.text = getString(if (ballOk) R.string.status_ball_on else R.string.status_ball_off)
        b.btnBallToggle.text = getString(
            if (ballOk) R.string.btn_ball_toggle_off else R.string.btn_ball_toggle_on
        )
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            // 个别 ROM 没有这个带 package 的入口，退回总入口
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }.onFailure { e -> toast("打不开悬浮窗设置：${e.message}") }
        }
    }

    /** 跳到系统无障碍设置页，并用高亮把我们的服务标出来 */
    private fun openAccessibilitySettings() {
        // 判据跟状态行保持一致（见 refreshStatus 的说明）：
        // 系统里勾了就算「已开」，不必再跳一次设置页
        if (ShotAccessibilityService.isEnabledInSystem(this)) {
            toast(getString(R.string.status_acc_on))
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            toast("请在列表里找到「咔嚓截屏 · 截图服务」并打开")
        }.onFailure { toast("打不开无障碍设置：${it.message}") }
    }

    // ==================== ② 发送目标区 ====================

    private fun setupTargetSection() {
        b.btnAddTarget.setOnClickListener { showAppPicker() }
    }

    private fun refreshTargets() {
        val list = prefs.targets
        val defPkg = prefs.defaultTargetPkg

        // 默认目标那一行
        val defLabel = prefs.defaultTargetLabel()
        b.tvDefaultTarget.text = defLabel.ifBlank { getString(R.string.target_none_chosen) }

        // 空态提示
        b.tvTargetEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        // 重建列表行
        b.boxTargets.removeAllViews()
        list.forEach { t ->
            val row = ItemTargetAppBinding.inflate(layoutInflater, b.boxTargets, false)
            val isDefault = t.pkg == defPkg

            row.tvAppName.text = t.label
            row.tvAppPkg.text = t.pkg

            // ⚠️ 图标给不了就留空 —— 不要用系统默认图标兜底，
            //    因为「全部手绘、不用系统图标」是本 App 的明确要求（用户定稿）。
            val icon = runCatching { packageManager.getApplicationIcon(t.pkg) }.getOrNull()
            if (icon != null) {
                row.ivAppIcon.setImageDrawable(icon)
                row.ivAppIcon.visibility = View.VISIBLE
            } else {
                row.ivAppIcon.visibility = View.GONE
            }

            // 默认标记
            if (isDefault) {
                row.btnMakeDefault.text = getString(R.string.target_default_tag)
                row.btnMakeDefault.setBackgroundResource(R.drawable.bg_chip_on)
                row.btnMakeDefault.setTextColor(getColor(R.color.accent))
            } else {
                row.btnMakeDefault.text = getString(R.string.target_set_default)
                row.btnMakeDefault.setBackgroundResource(R.drawable.bg_chip)
                row.btnMakeDefault.setTextColor(getColor(R.color.text_secondary))
                row.btnMakeDefault.setOnClickListener {
                    prefs.defaultTargetPkg = t.pkg
                    refreshTargets()
                    toast("默认改成「${t.label}」")
                }
            }

            // 整行点一下也设为默认（点着更顺手）
            if (!isDefault) {
                row.root.setOnClickListener {
                    prefs.defaultTargetPkg = t.pkg
                    refreshTargets()
                }
            }

            row.btnRemove.setOnClickListener {
                prefs.removeTarget(t.pkg)
                refreshTargets()
                toast("已移除「${t.label}」")
            }

            b.boxTargets.addView(row.root)
        }
    }

    /**
     * 弹一个底部列表，让用户从「手机里能收图的应用」中挑。
     *
     * ⚠️ 为什么要用「能收图」来筛，而不是列全部应用：
     *    ① 列全部要 QUERY_ALL_PACKAGES 高危权限，审核会被盘问
     *    ② 列了也没用 —— 用户选了不能收图的 App，点下去还是发不出去
     */
    private fun showAppPicker() {
        val apps = AppFinder.listImageReceivers(this)
        if (apps.isEmpty()) {
            toast("没找到能接收图片的应用")
            return
        }

        // ⚠️⚠️ 这里踩过真事故（v1.0 点「添加应用」秒退），三条铁律：
        //   ① **绝不调 dialog.requestWindowFeature(...)** ——
        //      它必须在 setContentView() **之前**调用，写在后面会抛
        //      AndroidRuntimeException: requestFeature() must be called before adding content。
        //      「无标题」已经写进 Theme.Kacha.Sheet，代码里碰都不碰它。
        //   ② Dialog 必须吃 Theme.Kacha.Sheet 主题 —— 默认继承 Activity 主题时
        //      windowIsFloating=false，弹层会退化成全屏窗口（dim / 布局全不对）。
        //   ③ window 的尺寸 / 位置 / dim 放在 show() **之后**设最稳
        //      （show 会重放一次 window attributes，提前设可能被覆盖）。
        val dialog = Dialog(this, R.style.Theme_Kacha_Sheet)
        val content = LayoutInflater.from(this).inflate(R.layout.sheet_pick_app, null)
        dialog.setContentView(content)

        val box = content.findViewById<LinearLayout>(R.id.boxPicker)
        val sv = content.findViewById<ScrollView>(R.id.svPicker)

        // 列表最高占屏幕 55% —— 应用多的时候能滚，少的时候不至于撑出一大块空白。
        // （布局里原来那个 android:maxHeight 是无效属性，ScrollView 压根没有它）
        val limitH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        runCatching { sv.layoutParams = sv.layoutParams.apply { height = limitH } }

        val already = prefs.targets.map { it.pkg }.toSet()

        apps.forEach { app ->
            val row = ItemPickAppBinding.inflate(LayoutInflater.from(this), box, false)
            row.tvPickName.text = app.label
            if (app.icon != null) {
                row.ivPickIcon.setImageDrawable(app.icon)
                row.ivPickIcon.visibility = View.VISIBLE
            } else {
                row.ivPickIcon.visibility = View.GONE
            }

            // 已添加过的给个视觉区分（名字变灰），避免用户重复添加
            if (app.pkg in already) {
                row.tvPickName.alpha = 0.45f
                row.tvPickName.text = "${app.label}  （已添加）"
            }

            row.root.setOnClickListener {
                val isNew = prefs.addTarget(app.label, app.pkg)
                refreshTargets()
                dialog.dismiss()
                toast(if (isNew) "已添加「${app.label}」" else "「${app.label}」已经在列表里了")
            }
            box.addView(row.root)
        }

        content.findViewById<View>(R.id.btnPickerCancel).setOnClickListener { dialog.dismiss() }

        // 列表填完后：内容比上限矮就缩回去（不然底下留一大片空白）
        box.post {
            runCatching {
                val need = box.height
                if (need in 1 until limitH) {
                    sv.layoutParams = sv.layoutParams.apply { height = need }
                }
            }
        }

        dialog.show()

        // ★ 尺寸 / 位置 / dim 一律放 show() 之后 —— 此时 window 已 attached，设了立刻生效
        runCatching {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val lp = attributes
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.BOTTOM
                attributes = lp
                setDimAmount(0.45f)
            }
        }
    }

    // ==================== ③ 动作开关区 ====================

    private fun setupSwitchSection() {
        bindSwitch(b.swGallery, { prefs.exportGallery }, { prefs.exportGallery = it })
        bindSwitch(b.swClipboard, { prefs.exportClipboard }, { prefs.exportClipboard = it })
        bindSwitch(b.swAutoSend, { prefs.exportAutoSend }, { prefs.exportAutoSend = it })
        bindSwitch(b.swBoot, { prefs.bootAutoStart }, { prefs.bootAutoStart = it })
    }

    /**
     * 统一的开关绑定。
     *
     * ⚠️ 先 setOnCheckedChangeListener 再 setChecked 会把回调打一遍 ——
     *    所以顺序是：**先 setChecked（此时还没监听器）→ 再挂监听器**。
     *    反过来的话，程序化设置也会触发写库，虽然值一样不会错，
     *    但万一以后有人在回调里做了别的动作就会出鬼。
     */
    private fun bindSwitch(
        sw: SwitchMaterial,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ) {
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    private fun refreshSwitches() {
        b.swGallery.isChecked = prefs.exportGallery
        b.swClipboard.isChecked = prefs.exportClipboard
        b.swAutoSend.isChecked = prefs.exportAutoSend
        b.swBoot.isChecked = prefs.bootAutoStart
    }

    // ==================== 版本 ====================

    private fun setupVersion() {
        b.tvVersion.text = getString(
            R.string.about_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
    }

    // ==================== 小工具 ====================

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { if (isFinishing) KachaApp.appContext?.let { /* 保持 Context 有效 */ } }
    }
}
