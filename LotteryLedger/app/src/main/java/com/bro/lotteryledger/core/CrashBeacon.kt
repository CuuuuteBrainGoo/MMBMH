package com.bro.lotteryledger.core

import android.content.Context
import java.io.File

/**
 * 崩溃信标 —— 让「闪退」这件事自己把证词留下来。
 *
 * ## 为什么需要它
 *
 * 2026-09-27 傍晚，少爷报「v1.5.0 打开就闪退」，而 1.4.0 正常。
 * 当时**拿不到崩溃日志**：
 *  - 手机没连电脑，`adb logcat` 用不了
 *  - App 自己的日志虽然会落盘，但那要能**进得去 App** 才看得到 ——
 *    而这次是**根本进不去**（点图标闪一下就没）
 *  - 系统「开发者选项 → 错误报告」对普通用户太重
 *
 * 结论：**一个进不去的 App，其内置日志功能等于不存在。**
 * 所以崩溃必须在**下一次启动**时就被认出来，并且能显示在一个**不依赖
 * 正常界面**的位置上。
 *
 * ## 工作方式
 *
 * 1. [markLaunched]：每次启动**一开始**就写 `last_launch` 时间戳 + 清掉旧崩溃记录。
 * 2. 崩溃发生时（由 `LedgerApp` 的未捕获处理器调 [record]）：把堆栈写进
 *    `last_crash.txt`。**写文件不经线程池、不经 sink** —— 那条路自己就可能崩。
 * 3. 下次启动：`last_crash.txt` 存在 → 说明上次没走到正常退出的路径 →
 *    界面上弹出来给用户看 / 复制 / 分享。
 *
 * ## 为什么标记和崩溃内容分两个文件
 *
 * 「上次非正常退出」和「上次崩在哪」是两件事。进程被系统杀（低内存）时
 * **没有崩溃堆栈**，但同样是非正常退出。用两个文件就能区分：
 *  - 只有 `last_launch` 是旧的、没有 `last_crash.txt` → 被系统杀了，不是 bug
 *  - 有 `last_crash.txt` → 真崩了，内容就是堆栈
 *
 * 这样「用户报闪退但其实是系统杀后台」的情况不会浪费一轮排查。
 */
object CrashBeacon {

    private const val DIR = "crash"
    private const val FILE_CRASH = "last_crash.txt"
    private const val FILE_LAUNCH = "last_launch"

    /** 崩一次最多留这么多字符。够看堆栈头部（那里才是有用的），又不会撑爆文件。 */
    private const val MAX_CHARS = 8000

    /**
     * 崩溃记录里「版本」那行的前缀。
     *
     * 单独抽出来是因为**界面层要靠它判断这条记录是不是旧版本留下的** ——
     * 2026-09-27：少爷升级到修复版后，旧的崩溃文件还在，
     * 于是「每次打开都弹同一个框」，而且框里完全没说这是旧记录。
     * 有了这个前缀，弹窗就能明说「这是 v1.5.1 的旧记录，当前版本已修复」。
     */
    private const val VERSION_LINE = "版本："

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun crashFile(ctx: Context) = File(dir(ctx), FILE_CRASH)
    private fun launchFile(ctx: Context) = File(dir(ctx), FILE_LAUNCH)

    /**
     * 从一段崩溃记录里抽出「崩在哪个版本」。
     *
     * 给界面层用来对比当前版本 —— 不一致就说明这是升级前的旧记录，
     * 应该显著提示而不是让用户以为「又崩了」。
     *
     * @return 形如 `1.5.1`；抽不出来返回 null。
     */
    fun versionOf(crashText: String): String? = try {
        crashText.lineSequence()
            .firstOrNull { it.startsWith(VERSION_LINE) }
            ?.removePrefix(VERSION_LINE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /**
     * 启动时调 —— **必须在 super.onCreate() 之后尽早**。
     *
     * 它做两件事：记下本次启动时间、并把上一次的崩溃内容**读出来返回**
     * （读完就删，避免用户每次开 App 都被同一份堆栈糊脸）。
     *
     * @return 上次崩溃的文本；没有则 null。
     */
    fun markLaunched(ctx: Context): String? {
        val prev = try {
            crashFile(ctx).takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()
        } catch (_: Throwable) {
            null
        }

        // 先读出内容再删 —— 顺序反了就永远读不到
        try {
            crashFile(ctx).delete()
        } catch (_: Throwable) {
        }

        try {
            launchFile(ctx).writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        } catch (_: Throwable) {
        }

        return prev?.takeIf { it.isNotEmpty() }
    }

    /**
     * 崩溃处理器里调。**只做最必要的事**：拼字符串 + 写文件。
     *
     * ⚠️ 这里**绝不能**调 [LedgerLog]、不能走线程池、不能申请锁 ——
     * 当前进程已经处于「随时会被杀」的状态，任何额外依赖都可能让这次写入也失败。
     * 直接 `writeText` 是最短路径。
     */
    fun record(ctx: Context, threadName: String, t: Throwable) {
        try {
            val text = buildString {
                append("=== 彩票账本崩溃记录 ===\n")
                append("时间：").append(ts())
                append("\n线程：").append(threadName)
                append("\n版本：").append(versionName())
                append("\n设备：Android ").append(android.os.Build.VERSION.RELEASE)
                append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）")
                append("\n机型：").append(android.os.Build.MANUFACTURER)
                append(" ").append(android.os.Build.MODEL)
                append("\n\n--- 异常 ---\n")
                append(t.javaClass.name).append(": ").append(t.message).append("\n\n")
                append(t.stackTraceToString())
                // 链式异常（Cause）也一并留下 —— 真正的根因常常在里面
                var cause = t.cause
                var depth = 0
                while (cause != null && depth < 4) {
                    append("\n\n--- cause ${depth + 1} ---\n")
                    append(cause.javaClass.name).append(": ").append(cause.message).append("\n")
                    append(cause.stackTraceToString())
                    cause = cause.cause
                    depth++
                }
            }
            crashFile(ctx).writeText(text.take(MAX_CHARS), Charsets.UTF_8)
        } catch (_: Throwable) {
            // 连崩溃都记不下来的话，也没别的办法了 —— 但绝不能在这里再抛
        }
    }

    /** 清掉崩溃记录（用户在界面上点「知道了」之后调）。 */
    fun clear(ctx: Context) {
        try {
            crashFile(ctx).delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * 「上次启动是否非正常结束」。
     *
     * 判据：上次记录的启动时间戳 + 一个宽松阈值 —— 如果距现在已经很久
     * （超过 [STALE_MS]），说明中间没正常启动过，属于可疑。
     *
     * 这个值目前只用于日志，不影响界面逻辑（界面一律以 `last_crash.txt` 为准），
     * 所以阈值取得很宽，宁可漏报也不误报。
     */
    fun lastLaunchAt(ctx: Context): Long? = try {
        launchFile(ctx).takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()?.toLongOrNull()
    } catch (_: Throwable) {
        null
    }

    private const val STALE_MS = 12L * 60 * 60 * 1000

    private fun ts(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND)
        )
    }

    /**
     * 版本号。**不引 BuildConfig** —— 核心层要保持能在纯 JVM 单测里跑。
     * 拿不到就返回 "?"，不影响崩溃记录本身的价值。
     */
    private fun versionName(): String = try {
        Class.forName("com.bro.lotteryledger.BuildConfig")
            .getField("VERSION_NAME").get(null) as? String ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
