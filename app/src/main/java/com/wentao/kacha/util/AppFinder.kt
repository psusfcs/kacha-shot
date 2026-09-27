package com.wentao.kacha.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * ============================================================================
 * 找「手机上哪些 App 能收图片」
 *
 * ── 为什么用 queryIntentActivities，而不是 getInstalledPackages ──
 *   ① getInstalledPackages 在 Android 11+ 要 QUERY_ALL_PACKAGES 权限，
 *      这是**高危权限**，应用商店审核会重点盘问「你凭什么枚举用户全部应用」。
 *   ② 我们其实只关心一件事：**谁能接住一张图**。
 *      安卓的标准做法就是问系统「Action 是 SEND、类型是 image 通配的接收者有哪些」。
 *      系统只会把真正声明了这个能力的 App 回给我们，天然就是筛选好的结果。
 *   ③ 顺带的好处：能收文字但不能收图的 App 不会混进来，
 *      免得用户选了「记事本」之后发现根本发不出去。
 *
 * ── ★ 注意：本文件注释里**绝不要写裸的星号斜杠组合** ──
 *   Kotlin 的块注释**支持嵌套**，所以注释正文里如果出现一个
 *   「斜杠 + 星号」的组合，会被语法解析器当成**又开了一层注释**，
 *   结果就是「外层注释永远关不上」—— 云编译直接报 Unclosed comment。
 *   写这类通配符时，用文字描述（如「image 通配」）或者把它拆开写。
 *
 * ── 顺带按「能直接分享图」的严格程度排个序 ──
 *   同时声明了 SEND + image 通配的 = 真的能收图（微信、豆包、DeepSeek 都在这类）；
 *   只声明 SEND 不限定类型的 = 大概率也能收，但排后面。
 * ============================================================================
 */
object AppFinder {

    private const val TAG = "KachaShot"

    /**
     * 一个可选的发送目标（比 [Prefs.Target] 多带一个图标）。
     * ⚠️ 图标单独放，因为 Drawable 不能序列化进 SharedPreferences，
     *    所以只有「选择那一刻」用得到，存库时不存图标。
     */
    data class AppItem(
        val label: String,
        val pkg: String,
        val icon: Drawable?
    )

    /**
     * 列出手机上所有能收图片的 App，并按「最可能用得上」排序。
     *
     * 排序规则（越靠前越可能是用户要找的）：
     *   ① 明确声明「image 通配」的（真·能收图）排在只声明通用 SEND 的前面
     *   ② 同档次内按名字拼音序（中文按 Unicode，勉强够用）
     *
     * @param exclude 要排除的包名（一般传自己的包名 + 系统分享面板）
     */
    fun listImageReceivers(context: Context, exclude: Set<String> = emptySet()): List<AppItem> {
        val pm = context.packageManager

        // 一次查询拿全部 —— 不要分两次（两次会各走一遍 IPC，慢一倍）
        val strict = querySenders(context, "image/*")
        val loose = querySenders(context, "*/*")

        // 严格的优先，宽松的补充在后面；同名包名去重
        val merged = LinkedHashMap<String, Pair<ResolveInfo, Boolean>>()
        strict.forEach { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@forEach
            merged[pkg] = ri to true
        }
        loose.forEach { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@forEach
            if (!merged.containsKey(pkg)) merged[pkg] = ri to false
        }

        val self = context.packageName
        return merged.entries
            .filter { (pkg, _) -> pkg != self && pkg !in exclude }
            .mapNotNull { (pkg, pair) ->
                val (ri, isStrict) = pair
                val label = runCatching {
                    ri.loadLabel(pm).toString().ifBlank { pkg }
                }.getOrDefault(pkg)
                val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                Triple(AppItem(label, pkg, icon), isStrict, label)
            }
            // 先按「能不能收图」降序（true 在前），再按名字升序
            .sortedWith(compareByDescending<Triple<AppItem, Boolean, String>> { it.second }
                .thenBy { it.third.lowercase() })
            .map { it.first }
    }

    /** 问系统：能接住 SEND + 指定 mimeType 的都有谁 */
    private fun querySenders(context: Context, mime: String): List<ResolveInfo> {
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                // ⚠️ 必须加 CATEGORY_DEFAULT，否则很多 App 的接收 Activity
                //    （在 manifest 里没写明 category 的）查不出来，结果就是列表空空的。
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
        }.onFailure { Log.w(TAG, "查询可分享应用失败（$mime）：${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * 查一个包名现在还装不装着、还收不收图。
     * 用于：用户之前添加的目标被卸载了 —— 首页要能如实反映，而不是等点下去才崩。
     */
    fun isStillAvailable(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                setPackage(pkg)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            ).isNotEmpty()
        }.getOrDefault(false)
    }

    /** 拿一个包名的显示名（用于补全列表里已经添加过、但当时没记下名字的项） */
    fun labelOf(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        return runCatching {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
    }
}
