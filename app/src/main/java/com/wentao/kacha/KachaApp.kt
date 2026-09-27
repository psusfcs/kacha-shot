package com.wentao.kacha

import android.app.Application
import android.util.Log
import com.wentao.kacha.util.Prefs

/**
 * ============================================================================
 * Application
 *
 * ── 为什么需要它 ──
 *   ① 留一份「全应用 Context」给静态入口用：
 *      悬浮球服务的实例可能被系统回收，这时首页要把服务重新拉起来，
 *      需要 Context 才能 startForegroundService。
 *   ② 兜住未捕获异常：真机闪退时至少能在 logcat 里留下真凶
 *      （没有它的话，进程直接没了，什么都看不到）。
 *
 * ⚠️ 这里绝不能做耗时的事（读大文件、网络、数据库初始化）——
 *    Application.onCreate 卡住 = 冷启动白屏，用户秒判「这 App 有问题」。
 *    我们这里只赋值一个 Context 引用，零耗时。
 * ============================================================================
 */
class KachaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // 装一个兜底：真机闪退时留个脚印，方便定位
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "未捕获异常（线程 ${t?.name}）：${e.message}", e)
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val TAG = "KachaShot"

        /**
         * 全应用 Context。悬浮球服务没起来时，首页靠它把服务拉起来。
         */
        @Volatile
        var appContext: android.content.Context? = null
            private set
    }
}
