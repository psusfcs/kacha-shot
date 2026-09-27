package com.wentao.kacha.util

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
 *   · 分享跳转**只能新开对话**，接不上用户正在聊的那条 —— 安卓分享机制
 *     不知道目标 App 内部有哪几个会话，这是系统层面的限制。
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
