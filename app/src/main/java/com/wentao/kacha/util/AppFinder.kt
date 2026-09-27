package com.wentao.kacha.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
     * 排序用的中间结构 —— 三个排序键提前算好，
     * 免得在比较器里反复查包信息（比较器会被调用 O(n log n) 次）。
     */
    private data class Scored(
        val item: AppItem,
        val strict: Boolean,   // 声明了「image 通配」= 真·能收图
        val system: Boolean,   // 系统应用
        val label: String
    )

    /**
     * 是不是系统应用。
     *
     * ★ 为什么要区分：用户点「添加应用」是要把图**发给谁** ——
     *   聊天/社交类 App 才对。而系统组件（打印、蓝牙、朗读、用户反馈）
     *   也会响应分享，混在最前面很干扰（v1.2 用户实测：列表里一屏全是这些）。
     *   做法是**排到后面**而不是过滤掉 —— 万一有人真想用「保存到本地」呢。
     */
    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean =
        runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        }.getOrDefault(false)

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

        // ★★ 按包名**分组**，不是简单覆写 —— 这是「列表里没有微信」的真凶。
        //
        //   一个 App 往往有**好几个分享入口**：微信就有「微信」「朋友圈」「收藏」三个，
        //   它们的包名都是 com.tencent.mm。v1.2 之前这里是 `merged[pkg] = ri`，
        //   谁最后遍历到谁就把前面的顶掉 —— 结果用户看到的是「朋友圈」，
        //   而**真正想找的「微信」压根没出现**。
        //   （用户原话：「连朋友圈都有为什么没有微信」——一句话定位到这儿。）
        //
        //   现在每个包先把所有入口收齐，再挑一个最能代表这个 App 的（见 pickMainEntry）。
        val byPkg = LinkedHashMap<String, MutableList<Pair<ResolveInfo, Boolean>>>()
        strict.forEach { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@forEach
            byPkg.getOrPut(pkg) { mutableListOf() }.add(ri to true)
        }
        loose.forEach { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@forEach
            // 已经有严格匹配入口的包就不再加宽松的了（同一入口别重复收）
            if (byPkg[pkg] == null) byPkg[pkg] = mutableListOf(ri to false)
        }

        val self = context.packageName
        return byPkg.entries
            .filter { (pkg, _) -> pkg != self && pkg !in exclude }
            .mapNotNull { (pkg, entries) ->
                val (ri, isStrict, label) = pickMainEntry(pm, pkg, entries)
                val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                Scored(AppItem(label, pkg, icon), isStrict, isSystemApp(pm, pkg), label)
            }
            // 排序三段：① 第三方 App 优先（系统组件沉底）
            //           ② 专收图片的优先（比只收通用分享的更可能用得上）
            //           ③ 同档次内按名字升序
            .sortedWith(
                compareBy<Scored> { it.system }
                    .thenByDescending { it.strict }
                    .thenBy { it.label.lowercase() }
            )
            .map { it.item }
    }

    /**
     * 一个 App 可能有好几个分享入口，挑出**最能代表它的那一个**。
     *
     * ★★ 为什么必须挑（而不是随便取一个）：微信的三个入口分别叫
     *    「微信」「朋友圈」「收藏」。用户要"发给微信"，
     *    那就必须把入口名叫「微信」的那个挑出来 —— 挑到「朋友圈」等于功能废了。
     *
     * 打分规则（越高越优先）：
     *   ① 入口名 **等于**应用名 → 这就是主入口（微信的聊天分享入口正好叫「微信」）
     *   ② 入口名 **包含**应用名（如「微信收藏」）→ 次优
     *   ③ 其余：**名字越短越可能更通用**（"朋友圈"比"朋友圈收藏"更像主入口）
     *   ④ 同分时优先「严格收图片」的那个
     *
     * @return Triple(入口信息, 是否严格收图片, 显示名)
     */
    private fun pickMainEntry(
        pm: PackageManager,
        pkg: String,
        entries: List<Pair<ResolveInfo, Boolean>>
    ): Triple<ResolveInfo, Boolean, String> {
        val appLabel = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")

        var bestRi = entries[0].first
        var bestStrict = entries[0].second
        var bestLabel = runCatching { bestRi.loadLabel(pm).toString() }.getOrDefault(pkg)
        var bestScore = Int.MIN_VALUE

        for ((ri, strict) in entries) {
            val lbl = runCatching {
                ri.loadLabel(pm).toString().ifBlank { pkg }
            }.getOrDefault(pkg)
            val base = when {
                appLabel.isNotBlank() && lbl.equals(appLabel, ignoreCase = true) -> 100_000
                appLabel.isNotBlank() && lbl.contains(appLabel) -> 50_000
                else -> 10_000 - lbl.length
            }
            val score = base + if (strict) 1 else 0
            if (score > bestScore) {
                bestScore = score
                bestRi = ri
                bestStrict = strict
                bestLabel = lbl
            }
        }
        return Triple(bestRi, bestStrict, bestLabel)
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
