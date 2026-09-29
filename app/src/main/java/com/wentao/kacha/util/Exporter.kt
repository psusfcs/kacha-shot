package com.wentao.kacha.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 截图导出三连：**存相册 + 放剪贴板 + 分享跳转**。
 *
 * ── 为什么要三个一起做（用户定案）──
 *   「A B C 可以一起走，我点完它进入新会话，同时进剪贴板，同时保存到相册，
 *     这是同一个动作完成的。」
 *
 *   三条路各有各的用处，哪条通走哪条：
 *     · 分享跳转 = 主力。图直接进目标 App 的输入框，最顺。
 *     · 存相册   = 兜底。万一分分享失败，用户自己能去相册找。
 *     · 剪贴板   = 备用。部分 App 支持粘贴图片。
 *
 * ── ⚠️ 三条路的已知限制（都不是 bug）──
 *   · 安卓分享机制不知道目标 App 内部有哪几个会话 —— 图永远到不了「正在聊的
 *     那条会话」手里，这是系统层面的限制。
 *   · ★ v2.1 起跳转分三路（见 FloatBallService.deliver 的分支）：
 *     目标就在眼前 → 不跳；后台还开着 → 原样切回它的窗口（bringToFront）；
 *     没开 → 走分享冷启动新窗口，图直接进输入框。
 *   · 剪贴板里的图片，很多 App（豆包、微信）**不认**，粘不出来。
 *     留着是因为"万一能用"，用户说用不上随时可以删。
 *   · 每截一次相册就多一张图，会越攒越多（用户已确认接受，自己统一删）。
 */
object Exporter {

    private const val TAG = "KachaShot"

    /** 相册里的子目录名 */
    private const val ALBUM = "咔嚓截屏"

    /** 分享用临时文件的目录（cacheDir/shares） */
    private const val SHARE_DIR = "shares"

    /**
     * 把截图存进系统相册。
     *
     * ── 为什么用 MediaStore 而不是直接写文件 ──
     *   Android 10+ 分区存储后，App 不能随便往公共图片目录写。
     *   走 MediaStore 是官方正路，而且 **Android 10+ 完全免存储权限**。
     *
     * @return 成功返回相册 uri，失败返回 null（失败不弹提示，静默降级）
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, nameHint: String = ""): Uri? {
        if (bitmap.isRecycled) return null
        return runCatching {
            val filename = buildName(nameHint)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            } ?: return@runCatching null
            // Android 10+ 写完要清 IS_PENDING，否则相册里看不到
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "已存相册：$filename")
            uri
        }.onFailure { Log.w(TAG, "存相册失败：${it.message}") }.getOrNull()
    }

    /**
     * 把截图放进系统剪贴板。
     *
     * ⚠️ 塞进去不代表能粘出来 —— 目标 App 收不收是它自己的事。
     *    不返回结果，纯尽力而为。
     */
    fun copyToClipboard(context: Context, bitmap: Bitmap) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // ⚠️ 必须用 ClipData.newUri() 或 setPrimaryClip(ClipData) 带 bitmap。
            //    常见写法 ClipData.newPlainText 只能放文字，图片进不去。
            val uri = saveTempForShare(context, bitmap)
            if (uri != null) {
                cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "截图", uri))
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("截图", ""))
            }
            Log.i(TAG, "已放剪贴板")
        }.onFailure { Log.w(TAG, "放剪贴板失败：${it.message}") }
    }

    /**
     * 分享跳转 —— 把图交给用户选定的目标 App。
     *
     * ── 为什么用系统分享（ACTION_SEND）而不是模拟点击 ──
     *   模拟点击要依赖目标 App 的控件 id / 文案，**它一改版就失灵**，是个无底洞。
     *   系统分享是安卓的正式协议，目标 App 改版一万次都不怕。
     *
     * ── 为什么先试「指定包名」再退回「通用分享」──
     *   `setPackage()` 能直达用户选定的那个 App（跳过选择面板，更省事）。
     *   但如果那个 App 被卸载了 / 不接收图片，会抛 ActivityNotFoundException，
     *   这时退回不指定包名的通用分享，让系统列出所有能收图的 App 兜底。
     *
     * @param targetPkg 目标 App 包名；空 = 走通用分享面板让用户自己选
     * @return true = 分享意图成功发出
     */
    fun shareToApp(context: Context, bitmap: Bitmap, targetPkg: String, nameHint: String = ""): Boolean {
        val uri = saveTempForShare(context, bitmap, nameHint) ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (targetPkg.isNotBlank()) {
                intent.setPackage(targetPkg)
            }
            context.startActivity(intent)
            Log.i(TAG, "已分享给 ${targetPkg.ifBlank { "(用户自选)" }}")
            true
        }.onFailure { e ->
            Log.w(TAG, "指定目标分享失败（${targetPkg}）：${e.message}")
            // 退回通用分享 —— 目标 App 可能被卸载 / 不收图片
            if (targetPkg.isNotBlank()) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    Log.i(TAG, "已退回通用分享面板")
                }.onFailure { Log.w(TAG, "通用分享也失败：${it.message}") }
            }
        }.isSuccess
    }

    // ==================== ★ v2.1 跳转窗口复用 ====================

    /**
     * 「后台还开着」的判定窗口：最近这么长时间内到过前台，就当它还开着。
     *
     * ★ 为什么取 12 小时：用户的使用习惯是「一堆常用 App 长期挂在后台」，
     *   取短了会把还挂着的 App 误判成「没开」→ 白白新开一个会话（用户最烦这个）。
     *   误判成「开着」的代价很小 —— 顶多是冷启动到它的首页，用户自己点进会话加图。
     *   所以宁可取长。
     */
    const val RECENT_ALIVE_MS = 12L * 60L * 60L * 1000L

    /**
     * 用户有没有开「使用情况访问权」。
     *
     * ★ 为什么需要这个权限：Android 8.0 起 getRunningAppProcesses 对普通应用
     *   只返回**自己的进程**（别的 App 死活一概看不见），官方唯一合规的路子是
     *   UsageStatsManager，而它要求这条特殊权限 —— 只能在系统设置里手动开。
     */
    fun hasUsageAccess(context: Context): Boolean {
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.onFailure { Log.w(TAG, "查使用情况权限失败：${it.message}") }
            .getOrDefault(false)
    }

    /**
     * 目标 App 最近有没有到过前台（近似「后台还开着」）。
     *
     * ★ 为什么用「最近到过前台」而不是「现在还活着」：系统不给普通应用看
     *   别的 App 的死活，只能拿使用记录近似 —— 最近刚用过的 App，多半还挂在
     *   后台（跟用户「我都在后台挂着呢」的直觉一致）。
     *
     * @return true = 判定为「还开着」；没授权 / 查不到一律 false（调用方走老路分享）
     */
    fun wasRecentlyOpen(
        context: Context,
        pkg: String,
        lookbackMs: Long = RECENT_ALIVE_MS
    ): Boolean {
        if (pkg.isBlank()) return false
        if (!hasUsageAccess(context)) return false
        return runCatching {
            val usm = context.getSystemService(UsageStatsManager::class.java)
                ?: return@runCatching false
            val end = System.currentTimeMillis()
            val start = end - lookbackMs
            val events = usm.queryEvents(start, end)
            var lastOpen = 0L
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                // MOVE_TO_FOREGROUND 就是「到前台来了」（API 29 起改叫 ACTIVITY_RESUMED，
                // 常量值相同，老名字在全部支持版本上都能用）
                if (e.packageName == pkg && e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (e.timeStamp > lastOpen) lastOpen = e.timeStamp
                }
            }
            lastOpen >= start
        }.onFailure { Log.w(TAG, "查使用记录失败：${it.message}") }.getOrDefault(false)
    }

    /**
     * 把目标 App 现有的窗口**原样**切回前台 —— 等价于用户从桌面点它的图标。
     *
     * ★ 为什么这样就「不新开会话」：这里发的是普通的启动意图（打开 App 本体），
     *   不是分享意图。系统按任务栈找它已有的窗口，找到了就原样带到前台，
     *   用户上次停在哪个界面就回到哪个界面。而 shareToApp 走 ACTION_SEND，
     *   目标 App 一看是分享来的，就自己开个新会话 —— 这正是 v2.1 要改掉的。
     *
     * @return true = 已发出切换；没有桌面入口 / 被系统拦截 → false（调用方走老路）
     */
    fun bringToFront(context: Context, pkg: String): Boolean {
        return runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return@runCatching false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            Log.i(TAG, "已把 $pkg 的现有窗口切回前台")
            true
        }.onFailure { Log.w(TAG, "切回 ${pkg} 前台失败：${it.message}") }
            .getOrDefault(false)
    }

    /**
     * 把图写到 cacheDir/shares/ 并返回 FileProvider uri。
     *
     * ⚠️ 为什么不能直接把 Bitmap 塞进 Intent：
     *   ① Bitmap 不可序列化，Intent 传不了（老写法要 putParcelable，1MB 以上直接
     *      TransactionTooLargeException 崩）。
     *   ② 分享给别的 App 必须走 content:// uri，不能是 file://
     *      （Android 7 起 file:// 会抛 FileUriExposedException）。
     *      所以必须配 FileProvider（见 res/xml/file_paths.xml）。
     */
    private fun saveTempForShare(context: Context, bitmap: Bitmap, nameHint: String = ""): Uri? {
        if (bitmap.isRecycled) return null
        return runCatching {
            val dir = File(context.cacheDir, SHARE_DIR).apply { if (!exists()) mkdirs() }
            val f = File(dir, buildName(nameHint))
            FileOutputStream(f).use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }.onFailure { Log.w(TAG, "准备分享文件失败：${it.message}") }.getOrNull()
    }

    private fun buildName(hint: String): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
            .format(java.util.Date())
        return "kacha_${hint.ifBlank { "" }}$ts.png".replace("__", "_")
    }
}
