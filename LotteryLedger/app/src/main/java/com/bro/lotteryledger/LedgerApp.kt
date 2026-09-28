package com.bro.lotteryledger

import android.app.Application
import com.bro.lotteryledger.core.CrashBeacon
import com.bro.lotteryledger.core.LedgerLog
import com.bro.lotteryledger.core.LogFileSink
import com.bro.lotteryledger.image.ImagePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LedgerApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 上次启动时崩掉留下的堆栈（没有则为 null）。
     *
     * 由 [onCreate] 从 [CrashBeacon] 读出来，界面层直接读它决定要不要弹「崩溃屏」。
     * 放在 Application 上而不是某个 ViewModel 里：**ViewModel 可能压根没活到
     * 被创建出来的时候** —— 而这份数据正是在那种场景下最有用。
     */
    var lastCrash: String? = null
        private set

    override fun onCreate() {
        super.onCreate()

        // ⚠️ 顺序有讲究：**先**装崩溃处理器 + 读上次崩溃记录，**再**做其它初始化。
        //
        // 起因（2026-09-27）：v1.5.0 点图标闪退，而当时没有任何手段拿到堆栈。
        // 如果处理器装在日志系统之后，日志系统自己出问题就没人兜底了。
        installCrashHandler()

        // 读上次崩溃记录。**只留「当前版本崩的」**：
        //
        // 2026-09-27 少爷反馈「升级后每次打开都弹同一个框」——
        // 那条是**升级前**（v1.5.1）留下的。既然已经装了修好的新版本，
        // 再拿旧记录糊他的脸只会制造"又崩了"的错觉。
        //
        // 但**不直接丢掉**：记进日志里留个底，万一以后回头查还有据可依。
        val rawCrash = CrashBeacon.markLaunched(this)
        val crashVer = rawCrash?.let { CrashBeacon.versionOf(it) }
        val isFromOldVersion = rawCrash != null && crashVer != BuildConfig.VERSION_NAME
        lastCrash = if (isFromOldVersion) null else rawCrash

        // 日志落盘（必须在最早时机装上，才能记到启动阶段的异常）
        val logSink = LogFileSink(this)
        logSink.install()

        // ⚠️ 日志输出**必须压在 sink 安装之后**：
        // 在装 sink 之前调 LedgerLog，那条只会进内存环形缓冲、不会落盘 ——
        // 而这几条正是最需要事后翻看的内容（启动期的异常）。
        if (rawCrash != null) {
            if (isFromOldVersion) {
                LedgerLog.w(
                    "Crash",
                    "发现旧版本($crashVer)的崩溃记录，已不再提示（当前 ${BuildConfig.VERSION_NAME}）。" +
                        "内容备查：\n${rawCrash.take(1200)}"
                )
            } else {
                LedgerLog.e("Crash", "检测到上次启动崩溃：\n${rawCrash.take(1500)}")
            }
        }

        // 日志按周清空（少爷 2026-09-27 要求：低频使用，不该无限增长）。
        // 同步做、且**在写启动日志之前** —— 顺序反了的话，刚写的「启动」那条
        // 会立刻被清掉，日志里看起来像少了一次启动。
        // 耗时是读一个几字节的标记文件，可忽略。
        logSink.sweepWeekly()

        LedgerLog.i("App", "启动 v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")

        // §3.2 异常清理：启动时扫自身临时目录，清掉闪退/断电遗留的图片。
        // 只动 cacheDir/ticket_tmp，绝不碰用户相册。
        appScope.launch {
            try {
                ImagePipeline(this@LedgerApp).sweepOrphans()
            } catch (e: Exception) {
                LedgerLog.e("App", "清理遗留临时图片失败", e)
            }
        }

        // 定时对奖：每天 22:30；Worker 内部会自我重排，这里只需排一次。
        //
        // ⚠️ 这一步是**同步**的，且在 `Application.onCreate` 里 ——
        // 它一旦抛异常，就是「点图标立刻闪退」，而且日志还没来得及建立上下文。
        // 所以单独包起来：排不上定时任务最多是「不自动对奖」，
        // 绝不该让整个 App 起不来（App 打开时还有一次补核验兜底）。
        try {
            com.bro.lotteryledger.net.PrizeCheckWorker.schedule(this)
        } catch (e: Exception) {
            LedgerLog.e("App", "排定定时对奖任务失败（不影响使用，打开 App 时会补核验）", e)
        }

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

    /**
     * 兜住所有未捕获异常：**先写崩溃信标，再写日志，最后交给系统**。
     *
     * 顺序不能反：
     *  1. [CrashBeacon.record] 走的是「直接写文件」最短路径，最不容易失败；
     *     [LedgerLog.e] 要经过线程池，进程将死时有可能来不及落盘。
     *     所以信标先写 —— 它是下次启动能不能看到堆栈的**唯一**依靠。
     *  2. 原始处理器（`prev`）必须调，否则 App 会卡死而不是退出（体验更差）。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // ① 信标：直接写文件，不走线程池、不走 sink
            CrashBeacon.record(this, t.name, e)

            // ② 日志：给能进 App 的场景留一份便于导出。它自己出问题也不能再抛。
            try {
                LedgerLog.e("Crash", "线程 ${t.name} 崩溃", e)
            } catch (_: Throwable) {
            }

            // ③ 给写日志线程一点时间落盘，否则进程一死日志就丢
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
            }

            prev?.uncaughtException(t, e)
        }
    }
}
