package com.wentao.kacha.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ★ v4.0 自研滚动截屏：自己滑、自己拼，**不用系统长截图**。
 *
 * 为什么不用系统长截图（用户明确要求）：
 *   系统长截图导出的图会被二次压缩，字糊成一团，AI 根本看不清。
 *   我们要的是「原始分辨率的每一帧，按重叠区严丝合缝拼起来」。
 *
 * 算法（三步）：
 *   ① **滑**：用无障碍的 dispatchGesture 模拟上滑（不是点击，是真正的手势滑动）
 *   ② **拍**：每次滑完等界面稳定，用无障碍截图抓一帧（原始分辨率，无压缩）
 *   ③ **拼**：拿新帧的顶部若干行，去上一帧里找「最像的位置」——
 *      那个位置就是重叠起点，从那儿往下接上，一张长图就成了
 *
 * 「到底了」的判断：
 *   连续 N 帧画面几乎完全一样 → 说明滑不动了（页面到底）→ 停止。
 *   另外还有两个保险：到达最大帧数、或者找不到有效重叠区。
 *
 * ⚠️ 已知限制（必须在 UI 上说清楚，不能让用户以为是 bug）：
 *   · 有**吸顶栏**的页面（微信聊天、淘宝详情）会出现「顶栏重复贴在中段」
 *   · **懒加载**页面（滚动才出图）拼接时下方可能是空白
 *   · 横屏、瀑布流错位布局的页面效果差
 *   拼歪时我们会**直接返回失败**并提示用户手动分次截图，绝不糊弄着拼一张烂图发出去。
 */
object ScrollCapture {

    private const val TAG = "ScrollCapture"

    /**
     * 找重叠区时，实际比对的采样行数。
     *
     * 为什么是 24：太少容易被「大片纯色背景」骗（比如白底页面，随便几行都长一样），
     * 太多又慢。24 行 × 每行 16 个采样点 = 384 个像素参与比对，够准也够快。
     */
    private const val MATCH_ROWS = 24

    /** 每行横向采样多少个点（不逐像素比，太慢） */
    private const val SAMPLE_COLS = 16

    /** 相似度阈值：平均色差小于这个值才算「匹配上」 */
    private const val MATCH_TOLERANCE = 12.0

    /**
     * 找重叠时，指纹取在画面的**百分之几处**（0.25 = 四分之一高度处）。
     *
     * ★★ 为什么不取顶部（0）—— v1.5 修的真 bug，用户实测「长图永远拼不上」：
     *    很多页面的顶部是**吸顶栏**（微信聊天顶上的标题栏就是）。
     *    滚动之后，新帧的顶部**还是那一模一样的标题栏** ——
     *    拿它去旧帧里找，必然在旧帧的**第 0 行附近**匹配上，于是算出
     *    「重叠 = 整屏」→ 判成「没滚动」/「找不到可靠匹配」→ 整套拼接作废。
     *    换成中段就骗不了了：中段内容是真的滚过去了的,
     *    它在旧帧里的位置就代表「这一屏往上走了多少」。
     *
     * ★ 为什么是 0.25（不是 0.5）：还要给「指纹位置 + 滚动距离」留出余量。
     *    默认每屏滚 70% 屏高，指纹 0.25 + 滚动 0.7 = 0.95，刚好落在扫描范围内。
     */
    private const val PROBE_RATIO = 0.25f

    /** 「这帧和上帧几乎一样」的判定阈值（用来判断到底了） */
    private const val STILL_TOLERANCE = 6.0

    /** 拼完之后最多保留多少行（防止超长图爆内存：12000 行 ≈ 4K 屏 4 屏高） */
    private const val MAX_RESULT_ROWS = 12000

    /** 滑完一屏后等界面稳定再截图（太快会拍到滚动中的糊帧） */
    private const val SETTLE_MS = 520L

    /** 系统限制无障碍截图频率约 1 张/秒，两帧之间至少隔这么久 */
    private const val MIN_FRAME_GAP_MS = 620L

    /** 连续多少帧没变化就认为到底（外部可配，这里给兜底默认） */
    private const val DEFAULT_STILL_FRAMES = 2

    /**
     * ★ v4.8：一屏滑动手势的时长（毫秒）。
     * 300ms 在部分 ROM 上会被判成「快速 fling」，滑过头直接跳过内容；
     * 450ms 更接近人手匀速滑，滚动距离可控、不会跳过。
     */
    private const val SCROLL_GESTURE_MS = 450L

    /**
     * 滚动截屏的进度回调（都跑在主线程）。
     *
     * @param frame  当前是第几屏（从 1 开始）
     * @param total  预计最多几屏（不是准确值，用来画进度）
     */
    interface Listener {
        fun onProgress(frame: Int, total: Int)
        fun onLog(message: String)
    }

    /** 结果：要么给一张长图，要么给「多屏原图」让 AI 自己读，要么彻底失败 */
    sealed class Result {
        data class Ok(val bitmap: Bitmap, val frames: Int) : Result()

        /**
         * ★ v4.1 新增：拼不成一张长图，但**手上有多屏好图**。
         *
         * 用户实测反馈：滚动截屏经常提示「长图没拼成，只截到一屏」——
         * 这等于功能白做。真因是拼接要求相邻两屏有 60%~70% 重叠，
         * 但实际滑动距离常常对不上，找不到可靠重叠区就直接判失败。
         *
         * 新策略（用户确认的方案）：**别硬拼，把多屏原图一起发给 AI**。
         * 视觉模型本来就能一次读多张图，而且原图没压缩、字清楚，
         * 比硬拼一张错位长图靠谱得多。
         *
         * @param shots 按上→下顺序的多屏原图（调用方负责回收）
         */
        data class Multi(val shots: List<Bitmap>) : Result()

        data class Fail(val reason: String, val partial: Bitmap? = null) : Result()
    }

    /**
     * 执行一次滚动截屏（**会阻塞调用线程**，请在 IO 线程调用，或用回调版）。
     *
     * @param service   无障碍服务实例（滑动和截图都靠它）
     * @param captureFn 抓一帧的函数（由调用方提供 —— 因为截图通道可能是无障碍也可能是投屏）
     * @param maxFrames 最多拼几屏
     * @param stillFrames 连续几帧没变化算到底
     * @param stepRatio 每屏滚动多少（占屏高比例）
     * @param cancelled ★ v5.3 外部取消信号 —— 每轮循环前后都查一次。
     *                  以前「停下」按钮只设了个变量没人读，等于装饰品，用户得干等十几秒。
     * @param listener  进度回调，可为 null
     */
    fun capture(
        service: AccessibilityService,
        captureFn: () -> Bitmap?,
        maxFrames: Int = 8,
        stillFrames: Int = DEFAULT_STILL_FRAMES,
        stepRatio: Float = 0.7f,
        cancelled: () -> Boolean = { false },
        listener: Listener? = null
    ): Result {
        val main = Handler(Looper.getMainLooper())

        // ① 先抓第一屏（还没滑动时）
        listener?.onLog("抓第 1 屏…")
        val first = captureFn() ?: return Result.Fail("第一屏就没截到，可能是有 App 正在播放视频或受保护内容")
        if (first.width <= 0 || first.height <= 0) {
            return Result.Fail("截图尺寸不对（${first.width}×${first.height}）")
        }
        listener?.onProgress(1, maxFrames)

        // ★ v5.3：用户在点「停下」后，把已抓到的帧拼出来给他（不白费）
        if (cancelled()) {
            listener?.onLog("用户点了停下，用已有帧收尾")
            return finishWithFrames(listOf(first), listener)
        }

        val frames = mutableListOf(first)
        var stillCount = 0
        // ★ v1.6：诊断计数 —— 失败时告诉用户「滑了几次、有没有滑成功」，
        //   不然他只能看到一句「滑不动」，没法反馈到底是哪种情况。
        var swipeAttempts = 0
        var swipeFailures = 0

        // ② 一屏一屏往下滑
        for (i in 2..maxFrames) {
            // ★ v5.3：每轮开头查一次「停下」。
            //   真因：v4.x 起 `scrollCancelled` 只被赋值、从来没人读，
            //   「停下」按钮是个纯装饰品 —— 用户点了还得干等十几秒跑完。
            if (cancelled()) {
                listener?.onLog("用户点了停下，用已有的 ${frames.size} 屏收尾")
                return finishWithFrames(frames, listener)
            }

            val step = (first.height * stepRatio).toInt().coerceAtLeast(80)

            // 滑：从屏幕下部往上滑 step 像素。用 gesture 而不是 scrollForward，
            // 因为 scrollForward 只在可滚动控件里好使，gesture 是「真手指」，通吃。
            var ok = swipeUp(service, step)
            if (!ok) {
                // ★ v4.1 第二通道：手势被拒（有些 App 自己吞手势）就用无障碍节点滚动
                listener?.onLog("手势滑不动，改用节点滚动…")
                ok = scrollForwardViaNode(service)
            }
            swipeAttempts++
            if (!ok) {
                swipeFailures++
                listener?.onLog("滑不动了（手势和节点都被拒）")
                break
            }

            Thread.sleep(SETTLE_MS)
            // 系统限制截图频率，两帧之间再等一下
            Thread.sleep(MIN_FRAME_GAP_MS)

            val bmp = captureFn() ?: break

            // ── 判断「这一帧有没有带来新内容」──────────────────────────
            // ★★ v1.3 修的真 bug（用户实测：「早翻到最底了，页面都没了，它还在计数」）：
            //    原来只看**整帧色差** —— 到底之后页面虽然不滚了，但滑动会触发
            //    overscroll（拉伸/回弹）动画，整帧色差**一直偏大**，
            //    `diff < STILL_TOLERANCE` 永远不成立 → 一路抓到底（直到帧数上限）。
            //    更糟的是：这些重复帧会被加进 frames，导致后面拼接找不到重叠
            //    → 整张长图作废 → 用户最后只拿到一屏。
            //
            //    改成看「**内容有没有真的位移**」（能不能在上一帧里找到这一帧的顶部）：
            //    这个判据不会骗人 —— 真滚了，就一定有重叠；没滚，重叠就是整屏。
            //    （findOverlap 内部已经把「重叠太多」归零了，所以 > 0 就是真滚了。）
            val diff = averageDiff(frames.last(), bmp)
            val scrolled = diff >= STILL_TOLERANCE && hasRealScroll(frames.last(), bmp)

            if (!scrolled) {
                stillCount++
                listener?.onLog("第 $i 帧没有新内容（色差 ${"%.1f".format(diff)}），丢弃")
                bmp.recycle()
                if (stillCount >= stillFrames) {
                    listener?.onLog("连续 $stillCount 帧没有新内容，判断已经到底")
                    break
                }
                continue
            }
            stillCount = 0

            frames.add(bmp)
            listener?.onProgress(frames.size, maxFrames)
            listener?.onLog("抓第 ${frames.size} 屏（跟上一屏差异 ${"%.1f".format(diff)}）")

            // ★ v5.3：抓完这一屏也查一次，点了停下就立刻收尾，
            //   不用等这一轮后面那些 sleep 走完。
            if (cancelled()) {
                listener?.onLog("用户点了停下，用已有的 ${frames.size} 屏收尾")
                return finishWithFrames(frames, listener)
            }

            // 结果太长就停手，不然内存扛不住
            if (frames.size * first.height > MAX_RESULT_ROWS * 2) {
                listener?.onLog("已经够长了，停止")
                break
            }
        }

        // ★ v4.1 只截到 1 屏：说明页面根本没滚动
        if (frames.size < 2) {
            frames.forEach { if (!it.isRecycled) it.recycle() }
            // ★ v1.6：把「滑了几次、滑没滑成」说清楚 —— 两种失败的根源完全不同：
            //   · 滑动本身失败（swipeFailures > 0）→ 系统没执行手势（权限 / 系统层问题）
            //   · 滑动成功但画面没变 → 页面真的不能滚，或者已经在底部
            //   用户看到数字才能准确反馈，我们才能对症下药。
            val why = if (swipeFailures > 0)
                "系统没有执行滑动（试了 $swipeAttempts 次）"
            else
                "滑了 $swipeAttempts 次、画面都没变化"
            return Result.Fail(
                "长截图没成：$why。这个页面可能不支持滚动，或者已经在底部。" +
                    "先给你这一屏。",
                null
            )
        }

        listener?.onLog("共 ${frames.size} 屏，开始拼接…")
        val stitched = stitch(frames, listener)

        return when {
            stitched != null -> {
                val count = frames.size
                frames.forEach { if (!it.isRecycled) it.recycle() }
                listener?.onLog("拼好了：${stitched.width}×${stitched.height}")
                Result.Ok(stitched, count)
            }

            else -> {
                // ★ v4.1 关键改动：拼不成一张长图 **不再直接判失败**，
                //   改成把这几屏原图打包交给上层，让 AI 一次读多张图。
                //   用户原话：「自动多截几屏，一起发给 AI」。
                //   原图没压缩、字清楚，比硬拼一张错位长图靠谱得多。
                listener?.onLog("拼不成一张长图，改成把 ${frames.size} 屏原图一起交给 AI")
                Result.Multi(frames.toList())
            }
        }
    }

    /**
     * ★ v5.3 新增：用户中途点「停下」时的收尾。
     *
     * ── 为什么要这个方法 ──
     * 老逻辑里用户点「停下」只有一个结局：`capture()` 直接 break 出去，
     * 然后因为 `frames.size` 不够 / 拼不上，把已抓的帧**全回收**，
     * 用户白等十几秒、最后什么都拿不到。用户原话：「我点了停下它没反应」。
     *
     * 现在的语义改成「**不白费**」：
     *   ① 只有 1 屏 → 没得拼，把这一屏当普通截图交出去（Fail.partial）；
     *   ② 有 ≥2 屏 → 试拼一次：
     *        拼成 → Ok（跟正常流程完全一样）
     *        拼不上 → Multi（把 N 屏原图交给上层，让 AI 一次读多张）
     *
     * ⚠️ 本方法**不回收**传进来的 frames —— 归属权交给返回的 Result，
     *    由上层（FloatBallService）按分支决定回收还是留着发出去。
     *
     * @param frames 已经抓到的帧（顺序：上 → 下）
     */
    private fun finishWithFrames(frames: List<Bitmap>, listener: Listener?): Result {
        val alive = frames.filter { !it.isRecycled && it.width > 0 && it.height > 0 }
        if (alive.isEmpty()) return Result.Fail("没抓到任何一屏", null)

        // 只有一屏：当单屏截图用，让它退回普通截图分析
        if (alive.size < 2) {
            listener?.onLog("只有 1 屏，按单屏截图处理")
            return Result.Fail("只抓到 1 屏，先给你这一屏，我照样能分析。", alive.first())
        }

        listener?.onLog("共 ${alive.size} 屏，收尾拼接…")
        val stitched = stitch(alive, listener)
        return if (stitched != null) {
            listener?.onLog("收尾拼好了：${stitched.width}×${stitched.height}")
            // 拼好了 → 原帧全部可以释放（长图已含全部内容）
            alive.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
            Result.Ok(stitched, alive.size)
        } else {
            listener?.onLog("收尾拼不上，改成把 ${alive.size} 屏原图交给 AI")
            Result.Multi(alive)
        }
    }

    /**
     * 用无障碍手势模拟「从下往上滑」。
     * 起点在屏幕 75% 高处、终点在 25% 高处 —— 中间那段才是有效滑动距离。
     *
     * ★ v4.3 关键修复（用户报「点完长截图，屏幕根本不动」）：
     *   真因有两条，都在这一个函数里：
     *   ① **必须在主线程调用**。`dispatchGesture` 是 UI 线程 API，
     *      而我们整个滚动流程跑在 `Dispatchers.IO` 的协程里 ——
     *      从子线程调它，系统会**静默返回 false 或直接不执行**，
     *      表现就是「点了长截图，屏幕纹丝不动，然后报失败」。
     *      修法：把真正的派发用 `handler.post` 甩回主线程，用 latch 等结果。
     *   ② **屏幕尺寸不能取 `service.resources.displayMetrics`**。
     *      无障碍服务进程的 metrics 在部分 ROM 上会被算成「去掉系统栏」的尺寸，
     *      算出来的滑动坐标就落偏了。改用 `WindowManager.currentWindowMetrics`。
     */
    private fun swipeUp(service: AccessibilityService, distance: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        // ★ 屏幕真实尺寸：优先用 WindowManager（含状态栏的完整屏幕）
        val size = runCatching {
            val wm = service.getSystemService(android.view.WindowManager::class.java)
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        }.getOrNull() ?: runCatching {
            val m = service.resources.displayMetrics
            Pair(m.widthPixels, m.heightPixels)
        }.getOrNull() ?: return false

        val w = size.first
        val h = size.second
        if (w <= 0 || h <= 0) return false
        val cx = w / 2f

        // 留出上下边界，别滑到状态栏/手势条上去
        val topLimit = h * 0.22f
        val bottomLimit = h * 0.78f
        var startY = bottomLimit
        var endY = startY - distance

        // 滑动距离不够时，把起点往上提，尽量凑出足够距离
        if (endY < topLimit) {
            endY = topLimit
            startY = (endY + distance).coerceAtMost(bottomLimit)
        }
        if (startY - endY < 60f) return false

        val path = android.graphics.Path().apply {
            moveTo(cx, startY)
            // ★ v4.8：改成平滑连续的一段（不再中途插一个折点）。
            //   折点会让部分 ROM 把这一整条手势判成「分两段」，第一段被当点击吞掉，
            //   屏幕上就看不到滚动 —— 表现为「长截图点了没反应」。
            lineTo(cx, endY)
        }

        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0, SCROLL_GESTURE_MS, false
        )
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            // ★ v4.8：显式给出可见画面边界，避免手势落到系统栏/挖孔区被系统丢弃
            .setDisplayId(android.view.Display.DEFAULT_DISPLAY)
            .build()

        var done = false
        val latch = java.util.concurrent.CountDownLatch(1)

        // ★ 关键：dispatchGesture 必须在主线程调
        val main = Handler(Looper.getMainLooper())
        main.post {
            runCatching {
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(
                            gestureDescription: android.accessibilityservice.GestureDescription?
                        ) {
                            done = true
                            latch.countDown()
                        }

                        override fun onCancelled(
                            gestureDescription: android.accessibilityservice.GestureDescription?
                        ) {
                            done = false
                            latch.countDown()
                        }
                    },
                    null
                )
                if (!dispatched) {
                    // 派发失败（被别的服务占用 / 权限不足），别把流程卡死
                    done = false
                    latch.countDown()
                }
            }.onFailure {
                done = false
                latch.countDown()
            }
        }

        // 等手势跑完（SCROLL_GESTURE_MS 手势 + 一点余量），超时也不算失败，继续往下走
        runCatching { latch.await(SCROLL_GESTURE_MS + 1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return done
    }

    /**
     * 把多帧竖着拼成一张长图。
     *
     * 核心难点是**找重叠区**：相邻两屏之间有一块内容是重复的，
     * 必须精确定位这块重复内容的起始行，才能严丝合缝接上（否则会出现「内容被拦腰切断」）。
     *
     * 做法：拿新帧的**顶部 MATCH_ROWS 行**当「指纹」，
     * 到上一帧里从头到尾扫一遍，找平均色差最小的那个位置 —— 那就是重叠起点。
     * 因为是按行比对，复杂度是 O(高度)，比逐像素比对快几个数量级，手机上跑得动。
     *
     * @return 拼好的长图；找不到可靠重叠区时返回 null（宁可不拼，也不拼歪）
     */
    private fun stitch(frames: List<Bitmap>, listener: Listener?): Bitmap? {
        // 先取出每一帧的「指纹」和「像素数组」，避免反复 getPixel（那个很慢）
        val pixels = frames.map { bmp ->
            val arr = IntArray(bmp.width * bmp.height)
            bmp.getPixels(arr, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            arr
        }

        val width = frames[0].width
        // 所有帧的宽必须一致，不一致说明中间屏幕转过/分辨率变了，直接放弃
        if (frames.any { it.width != width }) {
            listener?.onLog("各屏宽度不一致，放弃拼接")
            return null
        }

        // 每段：原图的起始行 + 要抄多少行
        data class Segment(val frame: Int, val fromRow: Int, val rows: Int)

        val segments = mutableListOf(Segment(0, 0, frames[0].height))
        var totalRows = frames[0].height

        for (i in 1 until frames.size) {
            val prev = frames[i - 1]
            val cur = frames[i]
            val curPx = pixels[i]
            val prevPx = pixels[i - 1]

            val overlap = findOverlap(
                prevPx, prev.height,
                curPx, cur.height,
                width
            )
            if (overlap <= 0) {
                listener?.onLog("第 ${i + 1} 屏跟上一屏对不上，放弃拼接")
                return null
            }

            // 重叠区在第 i 帧里是「顶部 overlap 行」，所以从 overlap 行往下的都是新内容
            val newRows = cur.height - overlap
            if (newRows <= 0) {
                listener?.onLog("第 ${i + 1} 屏整屏都是重复内容（可能滑不动了），跳过")
                continue
            }
            segments.add(Segment(i, overlap, newRows))
            totalRows += newRows

            if (totalRows > MAX_RESULT_ROWS) {
                listener?.onLog("已超过最长限制，截断")
                totalRows = MAX_RESULT_ROWS
                break
            }
        }

        if (totalRows <= frames[0].height) {
            listener?.onLog("没有任何新内容可拼")
            return null
        }

        // 真正开始拼
        return try {
            val out = Bitmap.createBitmap(width, totalRows, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var destY = 0
            for (seg in segments) {
                val src = frames[seg.frame]
                val rows = minOf(seg.rows, totalRows - destY)
                if (rows <= 0) break
                val srcRect = Rect(0, seg.fromRow, src.width, seg.fromRow + rows)
                val dstRect = Rect(0, destY, src.width, destY + rows)
                canvas.drawBitmap(src, srcRect, dstRect, paint)
                destY += rows
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "拼接失败：${e.message}")
            null
        }
    }

    /**
     * 在 prev 里找 cur 里某一小段内容出现在哪一行。
     *
     * ★★ v1.5 关键修正：指纹**不再取 cur 的顶部**，改取**中段**（见 [PROBE_RATIO]）。
     *    原因：顶部常被**吸顶栏**占着，滚动后它原封不动，
     *    拿它去找必然在 prev 顶部匹配到 → 算出「重叠=整屏」→ 直接判失败。
     *    这就是用户实测「长图永远拼不上、永远只给一屏」的真凶。
     *
     * @return 重叠行数（cur 顶部有 overlap 行和 prev 底部重复）；
     *         0 表示没找到可靠匹配（宁可不拼）
     */
    private fun findOverlap(
        prevPx: IntArray, prevH: Int,
        curPx: IntArray, curH: Int,
        width: Int
    ): Int {
        val rows = minOf(MATCH_ROWS, curH / 3)
        if (rows < 4 || prevH < rows + 8) return 0

        // 自适应步长：页面越高，扫描步长越大，保证耗时可控
        val step = (prevH / 260).coerceAtLeast(1)

        // ★ 指纹位置：cur 的中段（避开顶部吸顶栏，也不碰最底下）
        val probeRow = (curH * PROBE_RATIO).toInt().coerceIn(1, curH - rows - 1)

        var bestRow = -1
        var bestScore = Double.MAX_VALUE

        // 从 prev 的第 1 行开始扫（第 0 行是上一屏的顶，通常是状态栏，干扰大）
        var r = 1
        while (r <= prevH - rows - 1) {
            val score = rowBlockDiff(prevPx, prevH, r, curPx, probeRow, rows, width)
            if (score < bestScore) {
                bestScore = score
                bestRow = r
            }
            r += step
        }

        if (bestRow < 0 || bestScore > MATCH_TOLERANCE) return 0

        // bestRow = 「cur 的 probeRow 那一行」在 prev 里的位置。
        // 那么 cur 的**第 0 行**在 prev 里位于 (bestRow - probeRow)。
        val topInPrev = bestRow - probeRow
        if (topInPrev <= 0) {
            // cur 第 0 行落在 prev 顶部或之上 → 几乎没滚动（或者往上跑了），别拼
            return 0
        }

        // prev 从 topInPrev 往下的部分，跟 cur 的开头是重复的 —— 这就是重叠行数
        val overlap = prevH - topInPrev
        // 重叠太少说明基本没重合（可能是页面跳变了）；太多说明滑得太近，收益低
        if (overlap < 40 || overlap > prevH - 10) return 0
        return overlap
    }

    /** 比较两块行区域的相似度：返回平均色差（0 = 完全一样），越小越像 */
    private fun rowBlockDiff(
        a: IntArray, aH: Int, aRow: Int,
        b: IntArray, bRow: Int,
        rows: Int, width: Int
    ): Double {
        var sum = 0.0
        var n = 0
        // 横向按固定间隔采样，不逐像素（逐像素太慢，24×1080 就是 2.6 万次比对/位置）
        val colStep = (width / SAMPLE_COLS).coerceAtLeast(1)

        for (dr in 0 until rows) {
            val ar = aRow + dr
            val br = bRow + dr
            if (ar >= aH) break
            var c = 0
            while (c < width) {
                val pa = a[ar * width + c]
                val pb = b[br * width + c]
                val da = kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                val dg = kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
                val db = kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
                sum += (da + dg + db) / 3.0
                n++
                c += colStep
            }
        }
        return if (n == 0) Double.MAX_VALUE else sum / n
    }

    /** 两张图整体有多不一样（用来判断「滑不动了」）：0 = 一模一样 */
    private fun averageDiff(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return Double.MAX_VALUE
        val w = a.width
        val h = a.height
        val step = (h / 60).coerceAtLeast(1)   // 只采样 60 行，够判断了
        val colStep = (w / 24).coerceAtLeast(1)

        val arrA = IntArray(w * h)
        val arrB = IntArray(w * h)
        a.getPixels(arrA, 0, w, 0, 0, w, h)
        b.getPixels(arrB, 0, w, 0, 0, w, h)

        var sum = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val idx = y * w + x
                val pa = arrA[idx]
                val pb = arrB[idx]
                val da = kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                val dg = kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
                val db = kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
                sum += (da + dg + db) / 3.0
                n++
                x += colStep
            }
            y += step
        }
        return if (n == 0) 0.0 else sum / n
    }

    /**
     * 这一帧相对上一帧，**内容有没有真的往上走**？
     *
     * 判据就一条：能不能在「上一帧」里找到「这一帧的顶部」。
     *   · 真滚动了   → 一定有重叠区，[findOverlap] 返回 > 0
     *   · 没滚动     → 重叠 = 整屏，而 [findOverlap] 内部已经把「重叠太多」归零了
     *   · 页面跳变了 → 完全找不到，同样返回 0
     *
     * ★★ v1.3 新增：专门用来对付「到底之后的 overscroll 动画」——
     *    那种情况下页面被拉伸着，**整帧色差一直偏大**，但内容其实一步没动。
     *    只靠色差判据会一路抓到底，还把这些重复帧塞进 frames 把拼接搞崩。
     *
     * ⚠️ 出错时返回 true（当作"能滚"）—— 宁可多抓一帧，
     *    也别因为一次算法异常就把长截图卡死在第二帧。
     *
     * ⚠️ 这个判据比色差贵（要 getPixels 两帧 + 扫一遍），
     *    所以调用点做了短路：**只在色差判定「有变化」之后才来问它**。
     */
    private fun hasRealScroll(prev: Bitmap, cur: Bitmap): Boolean {
        if (prev.width != cur.width || prev.height != cur.height) return true
        val w = prev.width
        return runCatching {
            val prevPx = IntArray(w * prev.height)
            val curPx = IntArray(w * cur.height)
            prev.getPixels(prevPx, 0, w, 0, 0, w, prev.height)
            cur.getPixels(curPx, 0, w, 0, 0, w, cur.height)
            findOverlap(prevPx, prev.height, curPx, cur.height, w) > 0
        }.getOrDefault(true)
    }

    /**
     * 备用方案：用无障碍节点做「程序化滚动」。
     *
     * 有些 App（尤其是自己实现滚动的页面）对手势滑动不响应，
     * 但对无障碍的 ACTION_SCROLL_FORWARD 有效。主流程失败时可以试这个。
     *
     * ★ v4.3：同样必须跑在主线程 —— 无障碍节点操作跨进程，
     *   在子线程调会拿到过期的 root（或者直接返回 false）。
     */
    fun scrollForwardViaNode(service: AccessibilityService): Boolean {
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            ok = runCatching {
                val root = service.rootInActiveWindow ?: return@runCatching false
                try {
                    val node = findScrollable(root)
                    if (node != null) {
                        val r = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        node.recycle()
                        r
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "节点滚动失败：${e.message}")
                    false
                } finally {
                    runCatching { root.recycle() }
                }
            }.getOrDefault(false)
            latch.countDown()
        }
        runCatching { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return ok
    }

    /** 在节点树里找第一个能滚的容器 */
    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (node.isScrollable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findScrollable(child)
                if (found != null) {
                    if (found !== child) child.recycle()
                    return found
                }
                child.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "遍历节点失败：${e.message}")
        }
        return null
    }
}
