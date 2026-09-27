package com.wentao.kacha.ui

import android.app.Activity
import android.os.Bundle

/**
 * ============================================================================
 * 敲门页 —— 一闪而过的透明空页，用户完全看不见
 *
 * ── 它到底在干嘛 ──
 *   国内 ROM（HyperOS / MIUI / ColorOS）会周期性回收无障碍服务。
 *   回收之后经常出现一种尴尬状态：
 *     · 系统设置里明明**还勾选着**我们的截图服务
 *     · 但我们的进程里**拿不到实例**（服务被解绑了）
 *   这时用户点球 = 没反应，会以为 App 坏了。
 *
 *   系统有个特点：**只要发生真实的窗口切换，它就会重新检查一遍
 *   「勾选的无障碍服务都绑上了吗」**，没绑上的补绑。
 *
 *   所以这个页面的全部作用就是：**开一下、立刻关**，
 *   制造「打开 → 关闭」两波窗口状态变化，把系统「惊动」一下。
 *
 * ── 为什么它能被后台启动 ──
 *   Android 10+ 限制 App 在后台启动 Activity，
 *   但**持有 SYSTEM_ALERT_WINDOW（悬浮窗）权限的应用在豁免名单里**。
 *   而悬浮窗正是本 App 的命根子，所以我们天然有这个豁免。
 *
 * ── 佐证 ──
 *   老 App（全局AI助手）从 v3.x 起就用这招保活无障碍，真机验证有效。
 *
 * ⚠️ 这个页面**绝不能有任何 UI 或延时** ——
 *    多停留一毫秒，用户在切换应用时就可能瞥见一个白屏闪烁。
 *    所以：主题全透明 + 无动画 + onCreate 里立刻 finish + 不 setContentView。
 * ============================================================================
 */
class NudgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 不 setContentView —— 什么界面都不要
        // ★ 立刻结束：这一开一关就是我们要的「窗口切换信号」
        finish()
    }

    /**
     * 覆盖转场动画：不加的话，部分 ROM 会走一遍默认的 Activity 动画，
     * 用户会看到一个淡入淡出的白影。全部置零就没有任何视觉痕迹。
     */
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
