package com.wentao.kacha.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * 设置存取 —— 全 App 唯一的偏好存储
 *
 * ── 存了什么 ──
 *   · 悬浮球：开关 / 大小 / 透明度 / 自动隐身
 *   · 发送目标：已添加的应用清单（名字 + 包名）+ 默认是哪一个
 *   · 导出动作：存相册 / 放剪贴板 / 自动送过去 三个开关
 *   · 长截图参数：最多几屏 / 连续几帧没变算到底 / 每屏滚多少
 *
 * ── 为什么用 SharedPreferences 而不是 Room ──
 *   这个 App 要存的东西总共就十几项，全是「一个开关一个值」。
 *   上 Room 要引入 ksp 编译器 + 建表 + 写 DAO，纯属给自己找麻烦
 *   （老 App 用 Room 是因为要存聊天记录这种列表数据，这里没有）。
 *
 * ── 应用清单为什么序列化成 JSON 字符串 ──
 *   SharedPreferences 只能存基本类型 + String / Set<String>。
 *   应用是「名字 + 包名」一对一对的，用 Set<String> 塞 "name|pkg" 也能存，
 *   但改名字里的竖线就会炸。老老实实 JSON，稳。
 * ============================================================================
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ==================== 悬浮球 ====================

    /** 球是不是该运行（用户点了「启动悬浮球」之后为 true） */
    var ballEnabled: Boolean
        get() = sp.getBoolean(KEY_BALL_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_BALL_ENABLED, v).apply()

    /**
     * 球的大小（dp）。
     * ⚠️ 必须 clamp 在 [MIN_BALL_SIZE, MAX_BALL_SIZE] ——
     *    老 App 踩过：用户设成 10dp 球几乎点不着，设成 120dp 会盖住半个屏幕。
     */
    var ballSize: Int
        get() = sp.getInt(KEY_BALL_SIZE, DEFAULT_BALL_SIZE)
            .coerceIn(MIN_BALL_SIZE, MAX_BALL_SIZE)
        set(v) = sp.edit().putInt(KEY_BALL_SIZE, v.coerceIn(MIN_BALL_SIZE, MAX_BALL_SIZE)).apply()

    /**
     * 球的透明度（0.2 ~ 1.0）。
     * ⚠️ 下限 0.2：再低用户就找不着球了，会以为 App 坏了。
     *    老 App 的 Prefs.MIN_BALL_ALPHA 就是这个教训。
     */
    var ballAlpha: Float
        get() = sp.getFloat(KEY_BALL_ALPHA, 1f).coerceIn(MIN_BALL_ALPHA, 1f)
        set(v) = sp.edit().putFloat(KEY_BALL_ALPHA, v.coerceIn(MIN_BALL_ALPHA, 1f)).apply()

    /** 闲置一会儿自动缩成贴边小点 */
    var ballAutoHide: Boolean
        get() = sp.getBoolean(KEY_BALL_AUTO_HIDE, true)
        set(v) = sp.edit().putBoolean(KEY_BALL_AUTO_HIDE, v).apply()

    /** 开机自动启动悬浮球 */
    var bootAutoStart: Boolean
        get() = sp.getBoolean(KEY_BOOT_AUTO_START, true)
        set(v) = sp.edit().putBoolean(KEY_BOOT_AUTO_START, v).apply()

    // ==================== 发送目标 ====================

    /**
     * 一个目标 App。
     * @param label 显示名（可能跟系统名不完全一致，取自己起的友好名）
     * @param pkg   包名（跳转就靠它）
     */
    data class Target(val label: String, val pkg: String)

    /**
     * 用户添加的发送目标清单（按添加顺序）。
     *
     * ⚠️ 解析失败要能兜住 —— 用户换手机 / 清数据 / 手改文件都可能把 JSON 弄坏，
     *    坏了就当空清单，绝不能让首页崩。
     */
    var targets: List<Target>
        get() {
            val raw = sp.getString(KEY_TARGETS, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val pkg = o.optString("pkg", "")
                    if (pkg.isBlank()) return@mapNotNull null
                    Target(o.optString("label", pkg), pkg)
                }
            }.getOrDefault(emptyList())
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(
                    JSONObject().apply {
                        put("label", t.label)
                        put("pkg", t.pkg)
                    }
                )
            }
            sp.edit().putString(KEY_TARGETS, arr.toString()).apply()
        }

    /** 默认发给哪个包名（空 = 还没选） */
    var defaultTargetPkg: String
        get() = sp.getString(KEY_DEFAULT_TARGET, "").orEmpty()
        set(v) = sp.edit().putString(KEY_DEFAULT_TARGET, v).apply()

    /**
     * 加一个目标。已存在（同包名）就只更新显示名，不重复加。
     * @return true = 是新加的
     */
    fun addTarget(label: String, pkg: String): Boolean {
        val cur = targets
        if (cur.any { it.pkg == pkg }) {
            targets = cur.map { if (it.pkg == pkg) Target(label, pkg) else it }
            return false
        }
        targets = cur + Target(label, pkg)
        // 第一个加进来的自动当默认 —— 省得用户还得再点一次
        if (defaultTargetPkg.isBlank()) defaultTargetPkg = pkg
        return true
    }

    /**
     * 移除一个目标。
     * ⚠️ 如果删的正好是默认目标，要把默认清空或改到剩下的第一个 ——
     *    不然会留下一个指向「已卸载应用」的默认设置，用户以为坏了。
     */
    fun removeTarget(pkg: String) {
        targets = targets.filterNot { it.pkg == pkg }
        if (defaultTargetPkg == pkg) {
            defaultTargetPkg = targets.firstOrNull()?.pkg.orEmpty()
        }
    }

    /** 默认目标的显示名（给首页看） */
    fun defaultTargetLabel(): String =
        targets.firstOrNull { it.pkg == defaultTargetPkg }?.label.orEmpty()

    // ==================== 导出动作 ====================

    /** 截图同时存进相册（兜底） */
    var exportGallery: Boolean
        get() = sp.getBoolean(KEY_EXPORT_GALLERY, true)
        set(v) = sp.edit().putBoolean(KEY_EXPORT_GALLERY, v).apply()

    /** 截图同时放进剪贴板（备用） */
    var exportClipboard: Boolean
        get() = sp.getBoolean(KEY_EXPORT_CLIPBOARD, true)
        set(v) = sp.edit().putBoolean(KEY_EXPORT_CLIPBOARD, v).apply()

    /** 截完自动跳转到目标 App（主力） */
    var exportAutoSend: Boolean
        get() = sp.getBoolean(KEY_EXPORT_AUTO_SEND, true)
        set(v) = sp.edit().putBoolean(KEY_EXPORT_AUTO_SEND, v).apply()

    // ==================== 长截图参数 ====================

    /** 最多抓几屏（越大内存压力越大） */
    var scrollMaxFrames: Int
        get() = sp.getInt(KEY_SCROLL_MAX_FRAMES, 10).coerceIn(2, 20)
        set(v) = sp.edit().putInt(KEY_SCROLL_MAX_FRAMES, v.coerceIn(2, 20)).apply()

    /** 连续几帧画面没变化算「到底了」 */
    var scrollStillFrames: Int
        get() = sp.getInt(KEY_SCROLL_STILL_FRAMES, 2).coerceIn(1, 5)
        set(v) = sp.edit().putInt(KEY_SCROLL_STILL_FRAMES, v.coerceIn(1, 5)).apply()

    /**
     * 每屏滚动距离占屏高的比例。
     * ⚠️ 0.7 是经过验证的值：太小 → 屏数暴涨、拼半天；
     *    太大 → 相邻两屏重叠不够，拼不上（老 App 实测 0.75 就开始频繁拼不上）。
     */
    var scrollStepRatio: Float
        get() = sp.getFloat(KEY_SCROLL_STEP_RATIO, 0.7f).coerceIn(0.4f, 0.8f)
        set(v) = sp.edit().putFloat(KEY_SCROLL_STEP_RATIO, v.coerceIn(0.4f, 0.8f)).apply()

    companion object {
        private const val NAME = "kacha_prefs"

        private const val KEY_BALL_ENABLED = "ball_enabled"
        private const val KEY_BALL_SIZE = "ball_size"
        private const val KEY_BALL_ALPHA = "ball_alpha"
        private const val KEY_BALL_AUTO_HIDE = "ball_auto_hide"
        private const val KEY_BOOT_AUTO_START = "boot_auto_start"

        private const val KEY_TARGETS = "targets_json"
        private const val KEY_DEFAULT_TARGET = "default_target_pkg"

        private const val KEY_EXPORT_GALLERY = "export_gallery"
        private const val KEY_EXPORT_CLIPBOARD = "export_clipboard"
        private const val KEY_EXPORT_AUTO_SEND = "export_auto_send"

        private const val KEY_SCROLL_MAX_FRAMES = "scroll_max_frames"
        private const val KEY_SCROLL_STILL_FRAMES = "scroll_still_frames"
        private const val KEY_SCROLL_STEP_RATIO = "scroll_step_ratio"

        // 球的尺寸范围（dp）—— 见 ballSize 的注释
        const val MIN_BALL_SIZE = 36
        const val MAX_BALL_SIZE = 72
        const val DEFAULT_BALL_SIZE = 52

        /** 透明度下限：再低用户找不着球，会以为 App 坏了 */
        const val MIN_BALL_ALPHA = 0.2f
    }
}
