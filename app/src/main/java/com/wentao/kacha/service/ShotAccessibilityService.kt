package com.wentao.kacha.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * 截图无障碍服务 —— 「咔嚓截屏」唯一的系统能力来源。
 *
 * ── 为什么必须用无障碍 ──
 *   ① 截图：`takeScreenshot()`（Android 11+）开一次权限后**永久免弹框**；
 *      MediaProjection（投屏）每次都要弹授权框，用户嫌烦。
 *   ② 长截图：必须用 `dispatchGesture()` 模拟手指往上滑，全安卓只有无障碍能干。
 *      投屏授权只能「看」，不能「动」。
 *
 * ── 隐私 ──
 *   只申请「截图 + 手势」两项能力（见 res/xml/accessibility_service_config.xml），
 *   `canRetrieveWindowContent="false"` —— 不读屏幕上的任何文字、不监听输入。
 *
 * ── 保活说明 ──
 *   国内 ROM（HyperOS/MIUI 等）会周期性回收无障碍服务进程。
 *   本服务如实暴露 [isAlive] / [isEnabledInSystem]，UI 据此提示用户，
 *   不做激进的自动救援（那反而会被系统判定为异常行为而更容易被杀）。
 */
class ShotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastError = 0
        reapplyServiceInfo()
        Log.i(TAG, "截图无障碍服务已连接")
    }

    /**
     * 只关心「前台是哪个 App」，别的什么都不做。
     *
     * ★ 这个信息给「分享跳转」用：截完图要跳到目标 App 时，
     *   如果用户当前就在那个 App 里，就不用跳（避免自己跳自己）。
     *
     * ⚠️ 只取 `event.packageName` 这一个字符串，**不碰窗口节点、不读文字**。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return
        lastForegroundPackage = pkg
    }

    override fun onInterrupt() = Unit

    /**
     * 服务断开时清掉实例。
     *
     * ⚠️ 注意：HyperOS 强杀时**经常连 onUnbind 都不回调**。
     *   所以这里只是「能收到就赚到」，UI 那边仍然要主动查 [isAlive]。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "截图无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 把服务信息再刷一遍（保险丝）。
     *
     * 为什么需要：无障碍配置优先读 XML，但部分 ROM 会用「缓存里的旧配置」绑定服务 ——
     * 表现就是 `canTakeScreenshot` 偶发失效。连上之后用代码显式 set 一次更稳。
     *
     * ⚠️ `capabilities` 是**只读属性**（赋值会报 `'val' cannot be reassigned`），
     *    能力位只能靠 XML 里的 `canTakeScreenshot` / `canPerformGestures` 声明。
     *    这里只刷新可写的几个字段。
     */
    private fun reapplyServiceInfo() {
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.DEFAULT
            info.notificationTimeout = 100
            serviceInfo = info
        }.onFailure { Log.w(TAG, "刷新服务配置失败：${it.message}") }
    }

    /**
     * 抓一张截图。
     * 失败时回调 null，并把错误码写进 [lastError]（0 表示没出错）。
     *
     * ⚠️ 系统限制频率（约每秒 1 张），太密会返回 ERROR_TAKEN_TOO_FREQUENTLY(2)。
     *    长截图流程里必须靠 `MIN_FRAME_GAP_MS` 控制间隔。
     */
    fun capture(onDone: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            lastError = ERR_UNSUPPORTED
            onDone(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        lastError = 0
                        var out: Bitmap? = null
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            // 硬件位图不能直接压 JPEG，转成软件位图
                            out = hw?.copy(Bitmap.Config.ARGB_8888, false) ?: hw
                        } catch (e: Exception) {
                            Log.w(TAG, "转换截图为位图失败：${e.message}")
                        } finally {
                            runCatching { result.hardwareBuffer.close() }
                        }
                        onDone(out)
                    }

                    override fun onFailure(errorCode: Int) {
                        lastError = errorCode
                        Log.w(TAG, "截图失败，错误码 $errorCode（${describe(errorCode)}）")
                        onDone(null)
                    }
                }
            )
        } catch (e: Exception) {
            lastError = ERR_EXCEPTION
            Log.w(TAG, "调用截图接口异常：${e.message}")
            onDone(null)
        }
    }

    companion object {
        private const val TAG = "KachaShot"

        /** 服务连上后的实例；断开会置空 */
        @Volatile
        var instance: ShotAccessibilityService? = null
            private set

        /** 最近一次截图失败的错误码，0 = 没失败过 */
        @Volatile
        var lastError: Int = 0
            private set

        /** 最近一次前台 App 的包名（给「分享跳转」判断当前在哪） */
        @Volatile
        var lastForegroundPackage: String = ""
            private set

        /**
         * 实例是否**真的还能干活**。
         *
         * ⚠️ 不能只看 `instance != null`：进程被回收后重建时，
         *   静态引用可能还挂着，但对象已经和系统断连了（「假活」）。
         *   所以这里直接问系统「当前活跃的无障碍服务里还有没有我」。
         *
         * 假活时顺手把骗人的 instance 清掉，让 UI 立刻显示真实状态。
         */
        fun isAlive(context: Context): Boolean {
            val inst = instance ?: return false
            val alive = runCatching {
                val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                    ?: return@runCatching false
                am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                ).any { info -> (info.id ?: "").contains(context.packageName) }
            }.getOrDefault(false)
            // ⚠️ 只有在「系统里确实没勾」时才把骗人的实例清掉。
            //    原来是无条件清 —— 万一某个 ROM 的 getEnabledAccessibilityServiceList
            //    返回不准（这事有先例），会把**本来能用**的实例误杀，
            //    之后连截图都做不了。「真活被当成假活杀掉」比假活还糟。
            //    所以先拿系统设置兜一层：设置里明明勾着，就别动实例。
            if (!alive && !isEnabledInSystem(context) && inst === instance) {
                instance = null
            }
            return alive
        }

        /** 我们自己补的错误码（系统没定义的） */
        const val ERR_UNSUPPORTED = -1
        const val ERR_EXCEPTION = -2

        /** 系统：调用太频繁（约每秒限 1 次） */
        private const val ERROR_TAKEN_TOO_FREQUENTLY = 2

        fun isTooFrequent(errorCode: Int) = errorCode == ERROR_TAKEN_TOO_FREQUENTLY

        /** 把错误码翻译成人话 */
        fun describe(errorCode: Int): String = when (errorCode) {
            ERR_UNSUPPORTED -> "系统版本太低（需要 Android 11 以上）"
            ERR_EXCEPTION -> "调用截图接口异常"
            1 -> "系统不允许这个服务截图（无障碍权限可能被回收了）"
            ERROR_TAKEN_TOO_FREQUENTLY -> "截图太频繁，等一秒再试"
            else -> "错误码 $errorCode"
        }

        /**
         * 查系统设置，判断用户到底有没有开这个无障碍服务。
         *
         * 为什么不能只看 instance：进程被回收重建后 instance 会丢，
         * 但系统里依然是「已勾选」。这时用户以为开着，实际用不了。
         */
        fun isEnabledInSystem(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (flat.isBlank()) return false
            val full = ComponentName(context.packageName, ShotAccessibilityService::class.java.name)
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            for (item in splitter) {
                if (item.equals(full.flattenToString(), true) ||
                    item.equals(full.flattenToShortString(), true)
                ) return true
            }
            return false
        }

        /**
         * 跳到系统的「无障碍设置」页，让用户自己去开。
         *
         * ⚠️ 为什么不自动开：写 `Settings.Secure` 需要 WRITE_SECURE_SETTINGS，
         *    普通应用拿不到（只能 adb/root 授予），而且 putString 是**静默失败**——
         *    不抛异常也不生效，会让人误以为"已经开好了"。
         *    所以老老实实引导用户去设置页点开关。
         */
        fun openAccessibilitySettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.w(TAG, "打不开无障碍设置页：${it.message}") }
        }

        /**
         * 实例丢了但系统里开着 → 主动敲一下系统，让它补绑。
         *
         * 做法：开一个一闪而过的透明页（[com.wentao.kacha.ui.NudgeActivity]）。
         * 一开一关 = 两波真实的窗口状态变化 = 系统被惊动后检查
         * 「勾选的无障碍服务都绑上了吗」= 没绑上的补绑。
         *
         * 为什么后台能启 Activity：Android 10+ 管控后台启页，但**持有悬浮窗权限
         * （SYSTEM_ALERT_WINDOW）的应用在豁免名单里**，而悬浮窗正是本 App 的命根子。
         *
         * @return true = 已经敲过这一下（不代表一定成功）
         */
        fun requestSystemRebind(context: Context): Boolean {
            return runCatching {
                context.startActivity(
                    Intent(context, com.wentao.kacha.ui.NudgeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.onFailure {
                Log.w(TAG, "唤醒敲门页没起来：${it.message}")
            }.getOrDefault(false)
        }

        /**
         * 把服务叫醒：系统里开着但实例丢了 → 敲一下 + 轮询等它上来。
         *
         * 叫醒是异步的（系统 bind 要走一整套流程），所以做成「轮询 + 回调」。
         *
         * @param timeoutMs 最长等多久。3 秒 —— 1.5 秒经常不够（敲门页从启动到补绑
         *                  要走完整 Activity 生命周期），白白判死。
         */
        fun tryRevive(context: Context, timeoutMs: Long = 3000, onResult: (Boolean) -> Unit) {
            if (instance != null) {
                onResult(true)
                return
            }
            if (!isEnabledInSystem(context)) {
                onResult(false)
                return
            }
            requestSystemRebind(context)
            val handler = Handler(Looper.getMainLooper())
            val deadline = System.currentTimeMillis() + timeoutMs
            val tick = object : Runnable {
                override fun run() {
                    if (instance != null) {
                        onResult(true)
                        return
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        onResult(false)
                        return
                    }
                    handler.postDelayed(this, 200)
                }
            }
            handler.postDelayed(tick, 200)
        }
    }
}
