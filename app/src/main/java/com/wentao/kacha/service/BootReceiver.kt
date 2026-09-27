package com.wentao.kacha.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wentao.kacha.util.Prefs

/**
 * ============================================================================
 * 开机自启 —— 用户开了「开机自动启动悬浮球」才生效
 *
 * ── 为什么需要它 ──
 *   重启手机之后，前台服务全没了，用户得自己再点一次 App 才能把球找回来。
 *   开机自动拉起来，用户就感觉不到这个 App 存在 —— 这才是工具该有的样子。
 *
 * ── 为什么要在 Receiver 里再查一次设置 ──
 *   Receiver 是系统叫起来的，它不知道用户设了什么，
 *   所以必须自己去 Prefs 里确认一遍再决定拉不拉。
 *
 * ⚠️ 国内 ROM（HyperOS / MIUI）**默认不允许**开机自启，
 *    用户得去「手机管家 → 应用管理 → 权限 → 自启动」里单独放行。
 *    这不是 bug，是 ROM 的策略 —— 首页的说明里已经写清了这一点。
 *
 * ⚠️ Android 12+ 后台启动前台服务有限制，
 *    但 BOOT_COMPLETED 是**白名单豁免场景之一**，从这里启动是被允许的
 *    （不然就没有能开机自启的 App 了）。
 *    不过用户没给通知权限、或服务类型没声明时仍可能抛异常 —— 所以包了 runCatching。
 * ============================================================================
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != ACTION_QUICKBOOT_POWERON_HTC
        ) return

        val prefs = Prefs(context)
        if (!prefs.bootAutoStart || !prefs.ballEnabled) {
            Log.i(TAG, "开机自启：用户没开，跳过")
            return
        }
        Log.i(TAG, "开机自启：拉起悬浮球")
        FloatBallService.requestStart(context)
    }

    companion object {
        private const val TAG = "KachaShot"

        /**
         * 这两个是部分 ROM（HTC / 老 MTK 系）自己加的开机广播。
         *
         * ⚠️ 为什么不直接写在 inten-filter 里用字面量：
         *    这里定义成常量，清单里也写同样的字符串 ——
         *    两处必须一致，所以集中在这里说明来源，避免以后改一处漏一处。
         */
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
