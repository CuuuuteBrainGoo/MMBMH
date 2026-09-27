package com.bro.lotteryledger

import android.app.Application
import com.bro.lotteryledger.core.LedgerLog
import com.bro.lotteryledger.core.LogFileSink
import com.bro.lotteryledger.image.ImagePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LedgerApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // 日志落盘（必须在最早时机装上，才能记到启动阶段的异常）
        val logSink = LogFileSink(this)
        logSink.install()

        // 日志按周清空（少爷 2026-09-27 要求：低频使用，不该无限增长）。
        // 同步做、且**在写启动日志之前** —— 顺序反了的话，刚写的「启动」那条
        // 会立刻被清掉，日志里看起来像少了一次启动。
        // 耗时是读一个几字节的标记文件，可忽略。
        logSink.sweepWeekly()

        LedgerLog.i("App", "启动 v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")

        // 兜住所有未捕获异常：记进日志，少爷导出时能看到崩在哪
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            LedgerLog.e("Crash", "线程 ${t.name} 崩溃", e)
            // 给写日志线程一点时间落盘，否则进程一死日志就丢
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
            prev?.uncaughtException(t, e)
        }

        // §3.2 异常清理：启动时扫自身临时目录，清掉闪退/断电遗留的图片。
        // 只动 cacheDir/ticket_tmp，绝不碰用户相册。
        appScope.launch {
            ImagePipeline(this@LedgerApp).sweepOrphans()
        }

        // 定时对奖：每天 22:30；Worker 内部会自我重排，这里只需排一次
        com.bro.lotteryledger.net.PrizeCheckWorker.schedule(this)

        // 启动补核验：WorkManager 可能被系统压后，用户主动打开 App 时补一次。
        // **只在真有待核验的票时才跑网络**（少爷要求：没有待开奖的票就不做核对动作）。
        appScope.launch {
            try {
                val repo = com.bro.lotteryledger.repo.LedgerRepository(this@LedgerApp)
                val pending = repo.needsCheck()
                if (pending.isEmpty()) {
                    LedgerLog.d("Prize", "没有待核验的票，跳过启动补核验")
                    return@launch
                }
                LedgerLog.i("Prize", "启动补核验：${pending.size} 张已过开奖日")
                com.bro.lotteryledger.repo.PrizeService(this@LedgerApp).checkAllPending()
            } catch (e: Exception) {
                LedgerLog.e("Prize", "启动补核验失败", e)
            }
        }
    }
}
