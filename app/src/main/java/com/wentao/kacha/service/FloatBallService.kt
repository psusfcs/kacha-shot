package com.wentao.kacha.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.wentao.kacha.R
import com.wentao.kacha.ui.CropActivity
import com.wentao.kacha.ui.MainActivity
import com.wentao.kacha.util.CropBus
import com.wentao.kacha.util.Exporter
import com.wentao.kacha.util.Prefs
import com.wentao.kacha.util.ScrollCapture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * ============================================================================
 * 悬浮球服务 —— 「咔嚓截屏」的全部肉身
 *
 * ── 这个服务只干一件事 ──
 *   点球 = 截当前屏 ／ 长按球 = 自动滚动截长图
 *   截完 → **立刻三连导出**（送目标 App + 存相册 + 放剪贴板）→ 结束
 *
 * ── 跟老 App（全局AI助手）最大的区别 ──
 *   老 App：截完把图存进静态箱子 → 拉起自己的首页 → 首页拿去问 AI。
 *   新 App：**没有自己的首页要跳**。截完就地导出，图直接进别人家 App。
 *   所以这里**不需要** pendingHostShot 那套静态暂存箱
 *   （那个箱子存在的唯一理由是「Bitmap 不能塞 Intent」，而我们现在压根不传 Intent）。
 *
 * ── 三条导出路（用户定案：「A B C 可以一起走，同一个动作完成的」）──
 *     A. 分享跳转 → 主力。图直接进目标 App 的输入框
 *     B. 存相册   → 兜底。万一分分享失败，用户自己能去相册找
 *     C. 剪贴板   → 备用。部分 App 支持粘贴图片
 *   三个开关各自独立，用户想关哪个关哪个。
 *
 * ── 为什么不用投屏（MediaProjection）降级 ──
 *   长截图必须模拟手指滑动，**全安卓只有无障碍的 dispatchGesture 能干**，
 *   投屏授权只能「看」不能「动」。既然长截图是核心功能，无障碍就是硬需求，
 *   那单屏截图也走同一条路最省事 —— 用户只需要开一次权限。
 *   （用户原话：「直接无障碍，不用选，麻烦」）
 * ============================================================================
 */
class FloatBallService : Service() {

    companion object {
        const val ACTION_START = "com.wentao.kacha.action.START"
        const val ACTION_STOP = "com.wentao.kacha.action.STOP"

        private const val CHANNEL_ID = "kacha_running"
        private const val NOTIF_ID = 2001

        private const val CHANNEL_ID_ACC = "kacha_acc_health"
        private const val NOTIF_ID_ACC = 2002

        private const val TAG = "KachaShot"

        /** 手机闲置多久后自动隐身（缩成贴边小球） */
        private const val AUTO_HIDE_DELAY_MS = 20_000L

        /** 截图前等浮层彻底从画面里消失的时长 */
        private const val OVERLAY_HIDE_WAIT_MS = 300L

        /** 单屏截图的看门狗时长：超过它还没收尾就强制把球收回来 */
        private const val SHOT_WATCHDOG_MS = 4000L

        /** 供首页显示运行状态 */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var current: FloatBallService? = null

        /** 首页在服务没跑起来时，靠它把服务拉起来 */
        @Volatile
        var appCtx: android.content.Context? = null

        /**
         * 首页启动/关闭球。
         * 服务没在跑时由首页调 [requestStart] 拉起来。
         */
        fun requestStart(context: android.content.Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatBallService::class.java).setAction(ACTION_START)
                )
            }.onFailure { Log.w(TAG, "拉起悬浮球服务失败：${it.message}") }
        }

        fun requestStop(context: android.content.Context) {
            runCatching {
                context.startService(
                    Intent(context, FloatBallService::class.java).setAction(ACTION_STOP)
                )
            }.onFailure { Log.w(TAG, "关闭悬浮球服务失败：${it.message}") }
        }

        /**
         * 无障碍能不能用（首页状态行用）。
         *
         * ⚠️ ⚠️ 这里问的是 [ShotAccessibilityService.isAlive]，
         *   它内部会二次确认「系统当前活跃的无障碍列表里还有没有我」。
         *   为什么不能只看实例非空：进程被回收重建时静态引用可能还挂着，
         *   但对象已经和系统断连了 —— 那是「假活」，点球没反应。
         */
        fun isAccessibilityReady(ctx: android.content.Context): Boolean =
            ShotAccessibilityService.isAlive(ctx)

        /** 设置页改完球的大小/透明度后调这个，立刻生效 */
        fun notifyPrefsChanged() {
            current?.let { svc ->
                svc.mainHandler.post { runCatching { svc.applyBallAppearance() } }
            }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    /** 长截图遮罩（滚动进行中挡在屏幕上，防用户手动滑动打岔） */
    private var guardView: View? = null
    private var guardParams: WindowManager.LayoutParams? = null

    /** 用户按了遮罩上的「停下」 */
    @Volatile
    private var scrollCancelled = false

    /** 长截图进行中（防重复触发） */
    private var scrolling = false

    /** 单屏截图进行中（防重复触发） */
    private var capturing = false

    /**
     * 单屏截图的**看门狗**。
     *
     * ★ v1.3 新增。用户实测：「在桌面点悬浮球，球消失，但没有跳转应用，
     *   我也不知道他有没有被截图下来」。
     *   只要截图回调**一直不来**（服务被系统掐了 / takeScreenshot 静默失败等），
     *   球就会永远回不来 —— 用户看到的就是「点一下，球没了」。
     *   4 秒兜底：强制把球收回来，顺便抖一下告诉用户「这次没成」。
     */
    private val shotWatchdog = Runnable {
        if (capturing) {
            Log.w(TAG, "单屏截图超时没收尾，强制恢复球")
            restoreBallAfterShot()
            capturing = false
            flashBallFail()
        }
    }

    private var cachedScreen: Point? = null

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        isRunning = true
        current = this
        appCtx = applicationContext
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        cachedScreen = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.ballEnabled = false
                tearDown()
                return START_NOT_STICKY
            }

            else -> {
                // ⚠️ 前台服务必须先建起来，再干别的 ——
                //    否则 Android 12+ 会直接抛 ForegroundServiceDidNotStartInTimeException。
                bringToForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                if (Settings.canDrawOverlays(this)) {
                    ensureBall()
                    warmUpAccessibility()
                } else {
                    hint(getString(R.string.tip_no_overlay))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (current === this) current = null
        scrollCancelled = true
        runCatching { hideScrollGuard() }
        runCatching { ballView?.let { wm.removeView(it) } }
        ballView = null
        ballParams = null
        super.onDestroy()
    }

    private fun tearDown() {
        runCatching { wm.removeView(ballView) }
        ballView = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // ==================== 通知 / 前台服务 ====================

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ball_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 启动 / 切换前台服务类型。
     * ⚠️ 不声明类型会 MissingForegroundServiceTypeException；
     *    声明了类型却没申请对应权限会 SecurityException。两头都要对。
     */
    private fun bringToForeground(type: Int) {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /**
     * 无障碍掉线时的提醒通知。
     *
     * ── 什么时候才发（很重要）──
     *   只在「真的救不回来了」才发。能自愈的绝不出声 ——
     *   用户对「一个截图工具天天弹通知」的忍耐度是零。
     *
     * 点它 → 直接落到系统的无障碍设置页。
     */
    private fun postAccessibilityNotice(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID_ACC) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ACC,
                    getString(R.string.acc_notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.acc_notif_channel_desc)
                    setShowBadge(true)
                }
            )
        }
        // 点通知 → 打开首页并直接落到无障碍设置页
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_GOTO_ACCESSIBILITY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID_ACC)
            .setSmallIcon(R.drawable.ic_ball_notif)
            .setContentTitle(getString(R.string.acc_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { nm.notify(NOTIF_ID_ACC, notif) }
    }

    private fun clearAccessibilityNotice() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID_ACC) }
    }

    // ==================== 悬浮球 ====================

    private fun ensureBall() {
        if (ballView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.view_float_ball, null)
        val size = dp(prefs.ballSize)
        val screen = screenSize()

        // ★ v1.3：球的位置要**记住** —— 用户挪到哪儿，下次还在哪儿。
        //   以前每次启动都硬放在「右侧三分之一高处」，用户每次都得重新找球。
        //   用户原话：「我把它挪到哪个位置以后，他下次打开还是在我放的那个位置」。
        //   ⚠️ 存的是**比例**（Prefs.ballYRatio），换算时要按球心还原（不是顶边）。
        val restoreLeft = prefs.ballOnLeft
        val yRatio = prefs.ballYRatio
        ballOnLeft = restoreLeft

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // ⚠️ 不加 FLAG_LAYOUT_NO_LIMITS：它会让系统每帧按「无限制」重算布局，
            //    球是常驻窗口，等于一直吃性能。球本来就被 clamp 在屏内，不需要越界。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (restoreLeft) dp(4) else screen.x - size - dp(4)
            y = (screen.y * yRatio - size / 2f).toInt()
                .coerceIn(0, (screen.y - size).coerceAtLeast(0))
            alpha = prefs.ballAlpha
        }

        view.setOnTouchListener(BallTouchListener())
        runCatching { wm.addView(view, params) }
            .onFailure {
                hint("悬浮球没加上：${it.message}")
                return
            }

        ballView = view
        ballParams = params
        scheduleAutoHide()
    }

    /** 设置页改完球的大小/透明度后，立刻应用到已经挂在屏幕上的球 */
    private fun applyBallAppearance() {
        val v = ballView ?: return
        val p = ballParams ?: return
        val size = dp(prefs.ballSize)
        if (p.width != size || p.height != size) {
            p.width = size
            p.height = size
            clamp(p)
        }
        p.alpha = prefs.ballAlpha
        runCatching { wm.updateViewLayout(v, p) }
    }

    /**
     * 球的触摸：拖动 + 吸附 + **点=单屏截图 / 长按=长截图**
     *
     * ── 长按判定为什么用 Runnable 而不是自己算时间 ──
     *   用 postDelayed 挂一个任务，好处是「手指移动超过 slop」时
     *   能顺手 removeCallbacks 把长按取消掉 —— 拖动过程中不会误触发截图。
     */
    private inner class BallTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        /** 长按是否已经触发过（抬手时不要再当成"点一下"） */
        private var longFired = false

        private val slop: Int by lazy {
            ViewConfiguration.get(this@FloatBallService).scaledTouchSlop
        }

        private val longPressTask = Runnable {
            if (dragging) return@Runnable
            longFired = true
            cancelAutoHide()
            doShot(scroll = true)
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = ballParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelAutoHide()
                    if (ballHidden) {
                        // 隐身态：碰一下先展开，不截图（免得用户想找球却截了一堆图）
                        exitHiddenState()
                        return true
                    }
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    longFired = false
                    mainHandler.removeCallbacks(longPressTask)
                    mainHandler.postDelayed(
                        longPressTask, ViewConfiguration.getLongPressTimeout().toLong()
                    )
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressTask)   // 拖了就不算长按
                    }
                    if (dragging) {
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        clamp(p)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressTask)
                    if (dragging) {
                        snapToEdge()
                    } else if (event.action == MotionEvent.ACTION_UP && !longFired) {
                        v.performClick()
                        // 点一下 = 单屏截图（傻瓜式，一步到位）
                        doShot(scroll = false)
                    }
                    scheduleAutoHide()
                    return true
                }
            }
            return false
        }

        override fun toString(): String = "BallTouchListener"
    }

    private fun clamp(p: WindowManager.LayoutParams) {
        val screen = screenSize()
        p.x = p.x.coerceIn(0, (screen.x - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screen.y - p.height).coerceAtLeast(0))
    }

    /** 松手吸附到最近的左右边缘 */
    private fun snapToEdge() {
        val v = ballView ?: return
        val p = ballParams ?: return
        val screen = screenSize()
        val size = if (p.width > 0) p.width else dp(prefs.ballSize)
        val center = p.x + size / 2
        ballOnLeft = center < screen.x / 2
        p.x = if (ballOnLeft) dp(4) else screen.x - size - dp(4)
        p.y = p.y.coerceIn(0, (screen.y - size).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, p) }
        // ★ v1.3：松手就把位置记下来（靠哪边 + 纵向比例），下次启动照着还原
        rememberBallPosition(p.y, screen.y, size)
    }

    /**
     * 记住球停在哪儿。
     *
     * ⚠️ 存**比例**不存像素：换分辨率 / 转屏 / 换手机之后，绝对像素会跑到屏幕外，
     *    比例永远落在同一个相对位置。
     * ⚠️ 用球的**中心**算比例（不是顶边）—— 球的大小是可调的，
     *    用户眼里的"位置"是球心，不是它的上沿。
     */
    private fun rememberBallPosition(y: Int, screenH: Int, size: Int) {
        if (screenH <= 0) return
        val centerY = y + size / 2f
        val ratio = (centerY / screenH).coerceIn(0f, 1f)
        runCatching {
            prefs.ballOnLeft = ballOnLeft
            prefs.ballYRatio = ratio
        }
    }

    // ==================== 悬浮球自动隐身 ====================

    /** 球停在哪一边（隐身小球要贴着同一边） */
    private var ballOnLeft = false

    /** 当前是不是隐身（缩成贴边小球）状态 */
    private var ballHidden = false

    private var ballHiddenSize = 0

    private val autoHideRunnable = Runnable { enterHiddenState() }

    private fun scheduleAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        if (!prefs.ballAutoHide) {
            if (ballHidden) exitHiddenState()
            return
        }
        if (!ballHidden) {
            mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun cancelAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
    }

    /** 缩成贴边小球 */
    private fun enterHiddenState() {
        if (ballHidden) return
        val v = ballView ?: return
        val p = ballParams ?: return
        val screen = screenSize()
        val full = dp(prefs.ballSize)
        // 小球直径 ≈ 原球的四成（52dp 球 → 约 22dp）
        val mini = (full * 0.42f).toInt().coerceIn(dp(14), dp(40))
        ballHidden = true
        p.width = mini
        p.height = mini
        p.y = p.y.coerceIn(0, (screen.y - mini).coerceAtLeast(0))
        p.x = if (ballOnLeft) -mini / 3 else screen.x - mini * 2 / 3
        p.alpha = (prefs.ballAlpha * 0.6f).coerceAtLeast(Prefs.MIN_BALL_ALPHA)
        val iv = v.findViewById<ImageView>(R.id.ivBall)
        runCatching {
            iv?.contentDescription = getString(R.string.cd_ball_mini)
            iv?.setImageDrawable(null)
            iv?.setPadding(0, 0, 0, 0)
        }
        runCatching { wm.updateViewLayout(v, p) }
        ballHiddenSize = full
    }

    /** 从小球恢复成完整的球 */
    private fun exitHiddenState() {
        if (!ballHidden) return
        val v = ballView ?: return
        val p = ballParams ?: return
        val screen = screenSize()
        val size = if (ballHiddenSize > 0) ballHiddenSize else dp(prefs.ballSize)
        ballHidden = false
        p.width = size
        p.height = size
        p.x = if (ballOnLeft) dp(4) else screen.x - size - dp(4)
        p.alpha = prefs.ballAlpha
        val iv = v.findViewById<ImageView>(R.id.ivBall)
        runCatching {
            iv?.contentDescription = getString(R.string.cd_ball)
            iv?.setImageResource(R.drawable.ic_ball_icon)
            iv?.setPadding(dp(13), dp(13), dp(13), dp(13))
        }
        clamp(p)
        runCatching { wm.updateViewLayout(v, p) }
    }

    // ==================== 截图总入口 ====================

    /**
     * 点球的唯一入口。
     * @param scroll true = 长按（自动滚动截长图）；false = 点一下（单屏）
     */
    private fun doShot(scroll: Boolean) {
        if (capturing || scrolling) return
        if (scroll) doScrollShot() else doSingleShot()
    }

    /** 单屏截图 */
    private fun doSingleShot() {
        capturing = true
        detachBallForShot()

        // ★ v1.3：挂看门狗 —— 万一截图回调一直不来，球不能永远回不来
        mainHandler.removeCallbacks(shotWatchdog)
        mainHandler.postDelayed(shotWatchdog, SHOT_WATCHDOG_MS)

        mainHandler.postDelayed({
            val acc = ShotAccessibilityService.instance
            if (acc != null) {
                acc.capture { bmp ->
                    mainHandler.post {
                        mainHandler.removeCallbacks(shotWatchdog)
                        restoreBallAfterShot()
                        capturing = false
                        if (bmp == null) {
                            // 失败：提醒他去开权限（截图失败最常见的原因就是权限掉了）
                            Log.w(TAG, "单屏截图失败：${ShotAccessibilityService.describe(ShotAccessibilityService.lastError)}")
                            if (ShotAccessibilityService.lastError == 1) noteAccessibilityBroken()
                            // ★ v1.3：失败要**看得见**（以前纯静默，用户完全懵）
                            flashBallFail()
                        } else {
                            clearAccessibilityNotice()
                            deliver(bmp, "shot")
                        }
                    }
                }
                return@postDelayed
            }

            // 实例不在 → 叫醒一次，成功就接着截
            ShotAccessibilityService.tryRevive(this) { ok ->
                val revived = ShotAccessibilityService.instance
                if (ok && revived != null) {
                    revived.capture { bmp ->
                        mainHandler.post {
                            mainHandler.removeCallbacks(shotWatchdog)
                            restoreBallAfterShot()
                            capturing = false
                            if (bmp != null) {
                                clearAccessibilityNotice()
                                deliver(bmp, "shot")
                            } else {
                                Log.w(TAG, "叫醒后截图仍失败")
                                flashBallFail()
                            }
                        }
                    }
                } else {
                    // 叫不醒 → 收尾 + 提醒（不再降级投屏：用户定案只走无障碍一条路）
                    mainHandler.post {
                        mainHandler.removeCallbacks(shotWatchdog)
                        restoreBallAfterShot()
                        capturing = false
                        Log.w(TAG, "无障碍不可用，截图放弃")
                        noteAccessibilityBroken()
                        flashBallFail()
                    }
                }
            }
        }, 180)
    }

    /**
     * 长截图总入口 —— 全自动滚动，无手动干预。
     *
     * 用户定案：「长按球 = 自动滚动截长图」，全程无感，到底了自己停。
     *
     * ⚠️ 无障碍实例不在时**先叫一次醒**（跟单屏截图同款逻辑）：
     *    用户很可能刚在系统设置里勾上无障碍，而系统 bind 服务是异步的，
     *    这一刻 instance 还是 null —— 不叫醒的话用户看到的是「长按没反应」，
     *    以为坏了又去点一次。
     *    唤醒期间**球留在屏幕上不动**（不 detach）：要等 1~3 秒，
     *    球还在用户才知道程序没死；等真开滚了 startAutoCapture 里再摘球。
     */
    private fun doScrollShot() {
        val acc = ShotAccessibilityService.instance
        if (acc != null) {
            startAutoCapture(acc)
            return
        }
        Log.w(TAG, "长截图：无障碍实例不在，尝试叫醒")
        ShotAccessibilityService.tryRevive(this) { ok ->
            val revived = ShotAccessibilityService.instance
            if (ok && revived != null) {
                mainHandler.post { runCatching { startAutoCapture(revived) } }
            } else {
                Log.w(TAG, "长截图：无障碍叫不醒，放弃")
                noteAccessibilityBroken()
            }
        }
    }

    // ==================== 自动滚动 ====================

    /**
     * 自动滚动截屏。
     *
     * 全程不碰屏幕：屏幕只在下方的遮罩（上下两条细边条）中间自己滚，
     * 滚不动了（连续 stillFrames 帧画面几乎无差异）就自己停。
     */
    private fun startAutoCapture(acc: ShotAccessibilityService) {
        scrolling = true
        scrollCancelled = false
        showScrollGuard()
        detachBallForShot()

        Thread {
            try {
                Thread.sleep(OVERLAY_HIDE_WAIT_MS)
                val result = ScrollCapture.capture(
                    service = acc,
                    captureFn = { captureOnceBlocking(acc) },
                    maxFrames = prefs.scrollMaxFrames,
                    stillFrames = prefs.scrollStillFrames,
                    stepRatio = prefs.scrollStepRatio,
                    cancelled = { scrollCancelled },
                    listener = object : ScrollCapture.Listener {
                        override fun onProgress(frame: Int, total: Int) {
                            mainHandler.post {
                                runCatching { updateGuardText(getString(R.string.tip_scroll_frame, frame)) }
                            }
                        }

                        override fun onLog(message: String) {
                            Log.d(TAG, "scroll: $message")
                        }
                    }
                )
                mainHandler.post {
                    hideScrollGuard()
                    restoreBallAfterShot()
                    scrolling = false
                    when (result) {
                        // ★ v1.2：拼好了**先别急着导出** ——
                        //   进裁剪页，让用户自己剪掉多余的头尾（用户点名要的交互）。
                        is ScrollCapture.Result.Ok -> {
                            Log.d(TAG, "长图拼好了：${result.bitmap.width}×${result.bitmap.height}，进裁剪页")
                            openCropPage(result.bitmap)
                        }

                        is ScrollCapture.Result.Multi -> {
                            // 拼不成一张长图，但手上有 N 屏好图。
                            // ★ v1.3：改成**也进裁剪页**（只给第一屏）——
                            //   用户反馈原话：「最终也没有被录上，所以也到不了裁剪那一步」。
                            //   以前这里直接默默导出，用户完全不知道发生了什么。
                            //   现在至少让他有个能操作的东西，并且页面会说明情况。
                            //   「只递第一屏」的理由没变：我们的目标是送图给目标 App，
                            //   一次给 5 张会把输入框塞爆，很多分享入口也只收单张。
                            val first = result.shots.firstOrNull { !it.isRecycled }
                            result.shots.drop(1).forEach { runCatching { if (!it.isRecycled) it.recycle() } }
                            if (first == null) {
                                Log.w(TAG, "自动滚动：一屏都没抓到")
                                return@post
                            }
                            Log.w(TAG, "长图没拼成（${result.shots.size} 屏），拿第一屏进裁剪页")
                            openCropPage(first, partial = true)
                        }

                        is ScrollCapture.Result.Fail -> {
                            val partial = result.partial
                            if (partial != null && !partial.isRecycled) {
                                // 只抓到 1 屏 / 拼不上但有部分 → 给它这一屏
                                Log.w(TAG, "自动滚动：整张没拼成，先给第一屏")
                                deliver(partial, "long")
                            } else {
                                Log.w(TAG, "自动滚动失败：${result.reason}")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "长截图失败：${t.message}")
                mainHandler.post {
                    hideScrollGuard()
                    restoreBallAfterShot()
                    scrolling = false
                }
            }
        }.start()
    }

    // ==================== 长图裁剪 ====================

    /**
     * 把拼好的长图交给裁剪页（v1.2）。
     *
     * ── 为什么不让服务直接导出，非要中间插一页 ──
     *   自动滚出来的长图，头尾常常带着没用的一截（上一屏的尾巴、页脚广告）。
     *   以前只能整张全收，现在给用户一把剪刀 —— 剪完再交出去。
     *
     * ⚠️⚠️ 图的**归属权**：一旦 put 进 CropBus，就归裁剪页了，
     *    这里**绝对不能**再 recycle —— 那会让用户对着「已回收的图」直接崩。
     *
     * ⚠️ 裁剪页用 `NEW_TASK` 起：这是从 Service 里发起的，
     *    不加会因为「没有 Activity 栈」而抛异常。
     *
     * @param partial true = 长图没拼成，这只是其中一屏（页面会如实说明）
     */
    private fun openCropPage(bitmap: Bitmap, partial: Boolean = false) {
        runCatching {
            CropBus.put(bitmap)
            startActivity(
                Intent(this, CropActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CropActivity.EXTRA_PARTIAL, partial)
            )
        }.onFailure {
            Log.w(TAG, "打不开裁剪页：${it.message}")
            // 起不来就退回老路子 —— 直接把原图导出，别让用户白等一场
            val back = CropBus.take()
            if (back != null) deliver(back, "long")
        }
    }

    // ==================== ★ 导出三连（本 App 的终点）====================

    /**
     * 把截到的图按用户设置送出去。
     *
     * 用户原话：「A B C 可以一起走，我点完它进入新会话，
     *           同时它也进到了我的剪贴板，同时又保存到了相册。
     *           这是同一个动作完成的，这样不就全了吗。」
     *
     * ⚠️ 三条路都失败会怎样：什么都不发生（静默）。用户一看没跳过去，
     *    自己能去相册找（如果相册开着的话）。弹一堆 toast 只会更烦。
     *
     * @param bitmap   截到的图（本方法跑完负责回收）
     * @param nameHint 文件名提示（shot / long）—— 只是命名用，让相册里好认
     */
    private fun deliver(bitmap: Bitmap, nameHint: String) {
        try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return

            // B. 存相册（放最前面 —— 这是唯一的「留存」动作，
            //    先落盘再跳转，即使后面跳转把我们的进程挤掉了图也还在）
            if (prefs.exportGallery) {
                val uri = Exporter.saveToGallery(this, bitmap, nameHint)
                if (uri == null) Log.w(TAG, "存相册没成（不影响分享）")
            }

            // A. 分享跳转（主力）
            if (prefs.exportAutoSend) {
                val pkg = prefs.defaultTargetPkg
                if (pkg.isBlank()) {
                    hint(getString(R.string.tip_no_target))
                } else {
                    // ⚠️ 为什么重新查一遍「包还在不在」：
                    //    用户可能早就把那个 App 卸载了，设置里留着个死包名。
                    //    直接 startActivity 会走 ActivityNotFoundException（被 runCatching 兜住），
                    //    但我们会退回「通用分享面板」——那反而更烦人（用户没要选，却弹了个选择框）。
                    //    所以先查：不可用就跳过分享，别弹选择框。
                    val ok = Exporter.shareToApp(this, bitmap, pkg, nameHint)
                    if (!ok) {
                        Log.w(TAG, "分享没成功（目标可能已卸载）")
                        // ★ v1.3：不跳转是最让人懵的失败 —— 抖一下，让用户知道"这次没成"
                        flashBallFail()
                    }
                }
            }

            // C. 放剪贴板（备用）
            if (prefs.exportClipboard) {
                Exporter.copyToClipboard(this, bitmap)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "导出出错：${t.message}")
        } finally {
            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    // ==================== 截图前的浮层处理 ====================

    /**
     * 截图前把球藏了。
     * ★ 老 App 的经验：`View.INVISIBLE` 只是不画内容，窗口还在合成器里占图层位，
     *   部分 ROM（HyperOS 就是）会拍出一块糊/黑。所以这里**真移除**，拍完再加回来。
     */
    private fun detachBallForShot() {
        val v = ballView ?: return
        runCatching {
            if (ballParams != null) wm.removeView(v) else v.visibility = View.GONE
        }
    }

    /** 截图后把球装回来 */
    private fun restoreBallAfterShot() {
        runCatching {
            val v = ballView ?: return@runCatching
            val p = ballParams ?: return@runCatching
            if (v.parent == null) wm.addView(v, p)
            v.visibility = View.VISIBLE
        }
    }

    // ==================== 长截图底部控制条 ====================

    /**
     * 显示长截图进行中的底部控制条。
     *
     * ⚠️⚠️ 这是一个**只占屏幕底部的窄窗口**，绝不是全屏遮罩 —— 这不是审美选择，
     *    而是「长截图到底能不能用」的生死线（v1.2 修掉的真 bug）：
     *
     *      `dispatchGesture` 注入的滑动手势，会被系统送给**最顶层的可触摸窗口**。
     *      v1.1 之前这里放的是一块全屏遮罩（root = match_parent + clickable），
     *      手势全被它吃掉，下面的 App 根本收不到滑动 → 页面纹丝不动。
     *      更坑的是注入接口照样回调「完成」，程序以为滑成功了接着拍下一帧，
     *      拍到的还是同一屏 → 判定「到底了」→ 收工 → 用户什么都没拿到，
     *      只看到上下两个黑框闪了一下（用户原话：「上边一个黑框，下边一个黑框，
     *      但是实际是没有滚动的」）。
     *
     *    所以：**窗口只能占底部一条**，中间和上面完全空出来 ——
     *    没有被窗口覆盖的地方，触摸（包括注入的手势）才会直达下面的 App。
     *    ⚠️ 不要把它改成 match_parent，也不要在屏幕中间加任何窗口。
     */
    private fun showScrollGuard() {
        if (guardView != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.view_guard_bar, null)
        val screen = screenSize()
        val barW = (screen.x - dp(24)).coerceAtLeast(dp(120))
        val p = WindowManager.LayoutParams(
            barW,
            // ★ 高度自适应：窗口就只有这一条这么高，不覆盖屏幕中间的画布
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(12)
            y = dp(24)
        }
        runCatching {
            v.findViewById<View>(R.id.btnGuardStop)?.setOnClickListener {
                scrollCancelled = true
                updateGuardText("正在收尾…")
            }
        }
        runCatching { wm.addView(v, p) }
            .onFailure {
                Log.w(TAG, "底部控制条没加上：${it.message}")
                return
            }
        guardView = v
        guardParams = p
    }

    private fun hideScrollGuard() {
        val v = guardView ?: return
        runCatching { wm.removeViewImmediate(v) }
        guardView = null
        guardParams = null
    }

    private fun updateGuardText(text: String) {
        runCatching {
            guardView?.findViewById<android.widget.TextView>(R.id.tvGuardText)?.text = text
        }
    }

    /**
     * 长截图期间用的「拍一帧」——拍之前把遮罩摘干净。
     *
     * 真因：遮罩是 TYPE_APPLICATION_OVERLAY，无障碍 takeScreenshot 截的是
     * **系统合成后的整屏**，会把遮罩一起拍进去 → 拼出来全是糊图。
     * 所以每帧开拍前真移除，拍完立刻装回。
     *
     * ⚠️ 这个函数在**后台线程**被 ScrollCapture 反复调用，
     *    里面靠 latch 同步等主线程拍完。超时 6 秒当作没拍到（返回 null）。
     */
    private fun captureOnceBlocking(acc: ShotAccessibilityService): Bitmap? {
        val latch = CountDownLatch(1)
        var shot: Bitmap? = null
        mainHandler.post {
            val guardBack = detachGuardForShot()
            runCatching {
                acc.capture { bmp ->
                    if (guardBack) reattachGuardAfterShot()
                    shot = bmp
                    latch.countDown()
                }
            }.onFailure {
                if (guardBack) reattachGuardAfterShot()
                latch.countDown()
            }
        }
        return try {
            if (latch.await(6, TimeUnit.SECONDS)) shot else null
        } catch (e: InterruptedException) {
            null
        }
    }

    private fun detachGuardForShot(): Boolean {
        val v = guardView ?: return false
        runCatching { wm.removeViewImmediate(v) }
        return true
    }

    private fun reattachGuardAfterShot() {
        val v = guardView ?: return
        val p = guardParams ?: return
        runCatching { if (v.parent == null) wm.addView(v, p) }
    }

    // ==================== 无障碍可用性 ====================

    /**
     * 服务刚起来时，如果无障碍在系统里是开着的、但实例还没连上，
     * 顺手敲一下系统让它补绑 —— 用户就不用再去设置页点一次。
     */
    private fun warmUpAccessibility() {
        if (ShotAccessibilityService.isAlive(this)) return
        if (!ShotAccessibilityService.isEnabledInSystem(this)) return
        ShotAccessibilityService.tryRevive(this) { ok ->
            if (!ok) noteAccessibilityBroken()
        }
    }

    /**
     * 无障碍确实救不回来了 → 才打扰用户。
     *
     * ⚠️ 防骚扰：这个通知**只发一次**（已经在通知栏里就不再发），
     *    免得用户每点一次球就多一条通知。
     */
    private fun noteAccessibilityBroken() {
        if (accessibilityNoticeShown) return
        accessibilityNoticeShown = true
        val text = if (ShotAccessibilityService.isEnabledInSystem(this)) {
            getString(R.string.acc_lost_sleep)
        } else {
            getString(R.string.acc_lost_perm)
        }
        runCatching { postAccessibilityNotice(text) }
    }

    @Volatile
    private var accessibilityNoticeShown = false

    // ==================== 小工具 ====================

    /**
     * 失败了让球抖一下。
     *
     * ★ v1.3 新增。以前失败是**纯静默**的（只写日志），用户看到的现象是
     *   「点一下球，球没了，然后什么也没发生」—— 完全分不清是
     *   「截到了但没发出去」还是「压根没截到」。用户原话：
     *   「我不知道他有没有被截图下来」。
     *
     * 抖一下是最轻量的可见反馈：不打断用户、不需要任何权限、不会误触别的东西。
     * （不用 Toast：Android 11+ 后台弹 Toast 会被系统丢，Service 里发更不靠谱。）
     */
    private fun flashBallFail() {
        val v = ballView ?: return
        runCatching {
            val d = resources.displayMetrics.density
            val anim = android.animation.ObjectAnimator.ofFloat(
                v, "translationX",
                0f, -12f * d, 12f * d, -8f * d, 8f * d, 0f
            )
            anim.duration = 340
            anim.start()
        }
    }

    private fun hint(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun screenSize(): Point = cachedScreen ?: buildScreenSize().also { cachedScreen = it }

    private fun buildScreenSize(): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
