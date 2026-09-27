package com.wentao.kacha.util

import android.graphics.Bitmap

/**
 * ============================================================================
 * 长图从「悬浮球服务」交给「裁剪页」的中转站
 *
 * ── 为什么必须用静态字段，不能塞 Intent ──
 *   Bitmap 放进 Intent 要序列化（Parcelable），**超过 1MB 直接抛
 *   TransactionTooLargeException 崩掉**。长图动辄几 MB 甚至十几 MB，走 Intent 必死。
 *   同一个进程里用静态字段交接是零拷贝的，最稳。
 *   （老 App 的 pendingHostShot / PickedImageBus 也是这个套路，踩过的坑别再踩。）
 *
 * ── 为什么 take() 完就置空 ──
 *   只交接一次。留着的话：① 下次开裁剪页会拿到一张上一轮的旧图；
 *   ② 那张图可能已经被回收了（用一个 recycle 过的 Bitmap = 必崩）。
 * ============================================================================
 */
object CropBus {

    @Volatile
    private var pending: Bitmap? = null

    /** 服务拼好长图后放进来 */
    fun put(bitmap: Bitmap) {
        val old = pending
        pending = bitmap
        // 覆盖前把旧的回收掉，别让它悬着占内存
        if (old !== null && old !== bitmap) {
            runCatching { if (!old.isRecycled) old.recycle() }
        }
    }

    /** 裁剪页取走（取完即清空 —— 同一张图只能被交接一次） */
    fun take(): Bitmap? {
        val b = pending
        pending = null
        return b
    }

    /** 主动丢弃（用户取消裁剪时调，或者服务收尾时兜底） */
    fun clear() {
        val old = pending
        pending = null
        runCatching { if (old != null && !old.isRecycled) old.recycle() }
    }
}
