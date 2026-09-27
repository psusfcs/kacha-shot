package com.wentao.kacha.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wentao.kacha.R
import com.wentao.kacha.databinding.ActivityCropBinding
import com.wentao.kacha.util.CropBus
import com.wentao.kacha.util.Exporter
import com.wentao.kacha.util.Prefs

/**
 * ============================================================================
 * 长图裁剪页 —— 「截到哪儿、留哪段」由用户自己定
 *
 * ── 它解决什么问题 ──
 *   自动滚动截出来的长图，开头往往带着一截没用的（比如上一屏的尾巴），
 *   结尾也可能多出一块。以前只能整张全收，现在是：**两头随便剪**。
 *
 * ── 交互（对齐系统长截图那套，用户点名要的）──
 *   长图宽度撑满、可以上下滚着看；
 *   上下各一根把手，往下拖 = 砍掉开头，往上拖 = 砍掉结尾；
 *   点「完成」才真正导出，点「取消」就当没这回事。
 *   **只能裁上下，不能裁左右**（用户原话：「这个不能左右裁切，可以只能上下裁切。简单实用」）。
 *
 * ── 为什么自己调导出，不绕回悬浮球服务 ──
 *   少一次跨组件往返。图在这儿、用户在在这儿点了完成，
 *   直接调 Exporter 三连最直，出问题也只在一条链路上，好查。
 *
 * ── 图片怎么进来的 ──
 *   [CropBus]（同进程静态字段）。**绝不能走 Intent** ——
 *   长图几 MB，塞 Intent 必抛 TransactionTooLargeException。
 * ============================================================================
 */
class CropActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KachaShot"

        /**
         * 长图没拼成、只拿一屏过来时带这个 extra（v1.3）。
         * 带了就把底部提示换成「没拼成长图，这是第 1 屏」——
         * 用户得知道发生了什么，不能让他对着一张图猜「怎么只有一屏」。
         */
        const val EXTRA_PARTIAL = "partial"

        /** 至少保留这么高（dp）—— 别让用户一不留神把图裁成一条线 */
        private const val MIN_KEEP_DP = 48

        /** 把手高度的一半（布局里 handleTop/handleBottom 是 30dp） */
        private const val HANDLE_HALF_DP = 15
    }

    private lateinit var b: ActivityCropBinding
    private val ui = Handler(Looper.getMainLooper())

    /** 待裁剪的原图（本页负责它的回收） */
    private var src: Bitmap? = null

    /** 图片显示区域的总高（= boxCrop 高度）。布局测量完才有值 */
    private var boxH = 0f

    /** 当前裁剪区间的上下边界（**显示坐标**，相对 boxCrop 顶部） */
    private var cutTop = 0f
    private var cutBottom = 0f

    /** 拖把手的起点 */
    private var dragStartRawY = 0f
    private var dragStartCut = 0f

    /** 正在裁剪/导出 —— 防重复点击 */
    private var working = false

    /**
     * dp → px。
     * ⚠️ 跟项目其它文件保持一致：**返回 Int**（陷阱 ⑯）。
     *    View 的 translationY 是 Float，需要的地方自己 `.toFloat()` ——
     *    写成 Float 版 dp() 虽然本地能过，但会让整套校验的「Int 赋给 Float」检查失灵。
     */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCropBinding.inflate(layoutInflater)
        setContentView(b.root)

        // ★★ v2.0：targetSdk 35+ 强制 edge-to-edge，布局里的 fitsSystemWindows
        //   **不再自动避让状态栏** —— 工具栏会被顶进电池区（用户实测）。
        //   必须代码级把系统栏高度转成页面内边距。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val bitmap = CropBus.take()
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            // 拿不到图（进程被杀过 / 被别处清掉了）→ 直接退，别让用户对着空页面发愣
            toast(getString(R.string.crop_no_image))
            finish()
            return
        }
        src = bitmap
        b.ivCrop.setImageBitmap(bitmap)

        // 拼不成长图、只拿了一屏过来 → 如实告诉用户（别让他猜「怎么只有一屏」）
        if (intent?.getBooleanExtra(EXTRA_PARTIAL, false) == true) {
            b.tvCropHint.text = getString(R.string.crop_hint_partial)
        }

        b.btnCropCancel.setOnClickListener { cancelAndFinish() }
        b.btnCropDone.setOnClickListener { doCropAndExport() }
        attachDrag(b.handleTop, isTop = true)
        attachDrag(b.handleBottom, isTop = false)

        // ⚠️ 布局还没量完，此刻拿不到图片显示高度 —— 等第一帧排完再初始化区间
        b.boxCrop.post { initCutRange() }
    }

    /**
     * 初始化裁剪区间 = 全选（上下都不裁）。
     * ⚠️ 必须在测量完成后调，否则 boxH 是 0，算出来全是错的。
     */
    private fun initCutRange() {
        boxH = b.boxCrop.height.toFloat()
        if (boxH <= 0f) return
        cutTop = 0f
        cutBottom = boxH
        applyCut()
    }

    /**
     * 把「裁剪区间」画出来：遮罩高度 + 把手位置。
     *
     * 坐标系：cutTop / cutBottom 都是**相对 boxCrop 顶部**的显示坐标（px）。
     * 换算成原图像素要乘比例（见 [doCropAndExport]）。
     */
    private fun applyCut() {
        if (boxH <= 0f) return

        // 顶部遮罩：从 0 盖到 cutTop
        runCatching {
            val lp = b.maskTop.layoutParams
            lp.height = cutTop.toInt().coerceAtLeast(0)
            b.maskTop.layoutParams = lp
        }
        b.handleTop.translationY = cutTop - dp(HANDLE_HALF_DP).toFloat()

        // 底部遮罩：从 cutBottom 盖到底
        runCatching {
            val lp = b.maskBottom.layoutParams
            lp.height = (boxH - cutBottom).toInt().coerceAtLeast(0)
            b.maskBottom.layoutParams = lp
        }
        b.handleBottom.translationY = cutBottom - boxH + dp(HANDLE_HALF_DP).toFloat()
    }

    /**
     * 给把手挂拖动。
     *
     * ⚠️ 用 `rawY`（屏幕坐标）而不是 `y`：把手在 ScrollView 里，
     *    本地坐标会受滚动影响，rawY 才是手指真实移动量。
     * ⚠️ 监听器返回 true = 吃掉事件 → 拖把手时 ScrollView 不会跟着滚（这是对的）。
     * ⚠️⚠️ `coerceIn(min, max)` 在 **min > max 时会抛 IllegalArgumentException**！
     *    所以两个上/下界都必须先算好再兜底（下面 maxTop / minBottom 两行）。
     */
    private fun attachDrag(handle: View, isTop: Boolean) {
        handle.setOnTouchListener { _, e ->
            if (working || boxH <= 0f) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawY = e.rawY
                    dragStartCut = if (isTop) cutTop else cutBottom
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - dragStartRawY
                    val minKeep = dp(MIN_KEEP_DP).toFloat()
                    if (isTop) {
                        val maxTop = (cutBottom - minKeep).coerceAtLeast(0f)
                        cutTop = (dragStartCut + dy).coerceIn(0f, maxTop)
                    } else {
                        val minBottom = (cutTop + minKeep).coerceAtMost(boxH)
                        cutBottom = (dragStartCut + dy).coerceIn(minBottom, boxH)
                    }
                    applyCut()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** 取消 = 什么都不做（图丢掉）。符合「取消」的直觉，不会偷偷给用户塞一张图 */
    private fun cancelAndFinish() {
        if (working) return
        finish()
    }

    /**
     * 裁剪 + 导出。
     *
     * ⚠️ `Bitmap.createBitmap` 对大图是**几百毫秒级**的重活，必须离开主线程，
     *    否则会掉帧甚至 ANR。
     */
    private fun doCropAndExport() {
        if (working) return
        val source = src ?: return
        if (boxH <= 0f) {
            finish()
            return
        }

        // 显示坐标 → 原图坐标（长图被等比缩过，按比例还原）
        val ratio = source.height / boxH
        val y0 = (cutTop * ratio).toInt().coerceIn(0, source.height - 1)
        val y1 = (cutBottom * ratio).toInt().coerceIn(y0 + 1, source.height)

        working = true
        b.btnCropDone.alpha = 0.5f
        b.btnCropCancel.alpha = 0.5f

        Thread {
            val out = runCatching {
                Bitmap.createBitmap(source, 0, y0, source.width, y1 - y0)
            }.getOrNull()
            ui.post {
                if (out == null) {
                    working = false
                    b.btnCropDone.alpha = 1f
                    b.btnCropCancel.alpha = 1f
                    toast(getString(R.string.crop_failed))
                    return@post
                }
                exportAndFinish(out)
            }
        }.start()
    }

    /**
     * 把裁好的图交出去。
     * 顺序跟悬浮球那边完全一致：**存相册放最前**（唯一的「留存」动作，
     * 先落盘再跳转 —— 即使跳转把我们的进程挤掉，图也已经在相册里了）。
     */
    private fun exportAndFinish(bitmap: Bitmap) {
        val prefs = Prefs(this)
        try {
            if (prefs.exportGallery) {
                val uri = Exporter.saveToGallery(this, bitmap, "long")
                if (uri == null) Log.w(TAG, "裁剪后存相册没成（不影响分享）")
            }
            if (prefs.exportAutoSend) {
                val pkg = prefs.defaultTargetPkg
                if (pkg.isBlank()) {
                    toast(getString(R.string.tip_no_target))
                } else {
                    val ok = Exporter.shareToApp(this, bitmap, pkg, "long")
                    if (!ok) Log.w(TAG, "裁剪后分享没成功（目标可能已卸载）")
                }
            }
            if (prefs.exportClipboard) {
                Exporter.copyToClipboard(this, bitmap)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "裁剪后导出出错：${t.message}")
        } finally {
            // 裁切出来的这张图是我们自己造的，用完自己回收
            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ⚠️ 先把 ImageView 上的引用摘干净再回收 —— 否则可能撞上
        //    「绘制时用了已回收的 Bitmap」
        runCatching { b.ivCrop.setImageDrawable(null) }
        runCatching {
            val s = src
            if (s != null && !s.isRecycled) s.recycle()
        }
        src = null
        // 兜底：万一图还没被取走（异常退出等），别留个孤儿在静态字段里占内存
        CropBus.clear()
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
