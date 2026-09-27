package com.bro.lotteryledger.net

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bro.lotteryledger.core.LedgerLog
import com.bro.lotteryledger.repo.PrizeService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * 定时对奖（少爷 2026-09-26 定）。
 *
 * ## 规则
 *
 * ```
 * 开奖日 22:30  → 核验所有「已到开奖日」的票
 *        若官方还没出结果 → 次日 08:30 再核验一次
 * 没有待核验的票 → 什么都不做（不空跑网络请求）
 * ```
 *
 * ## 为什么不用 WorkManager 的周期性任务
 *
 * `PeriodicWorkRequest` 只能按「间隔」跑（最早 15 分钟一次），**没法指定"每天 22:30"**。
 * 所以改用**一次性任务自我重排**：每次跑完，算出「下一个 22:30 距今多少分钟」，
 * 再排一个新的一次性任务。这样就是精确的「每天 22:30」。
 *
 * ## 精度说明
 *
 * WorkManager 受系统 Doze 影响可能延迟几分钟到几十分钟。对彩票对奖来说无所谓
 * （开奖 21:30、公告 21:35 左右就出了，晚半小时查照样拿得到）。
 * 真要更准得用 `AlarmManager.setExactAndAllowWhileIdle`，但那要申请
 * `SCHEDULE_EXACT_ALARM` 权限（Android 12+ 还会跳设置页），为这个场景不值得。
 *
 * 另外 App 打开时也会补一次（`checkAllPending`），所以即使 WorkManager 被系统压后，
 * 用户主动打开 App 时也能拿到结果。
 */
class PrizeCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val UNIQUE_MAIN = "prize_check_daily"
        private const val UNIQUE_RETRY = "prize_check_retry"

        /** 主任务时间：开奖日 22:30。 */
        private val MAIN_TIME = LocalTime.of(22, 30)

        /** 补偿重试时间：次日 08:30。 */
        private val RETRY_TIME = LocalTime.of(8, 30)

        /**
         * 安排下一次核验。App 启动时调一次即可（Worker 内部会自我重排）。
         */
        fun schedule(context: Context) {
            val delay = minutesUntil(MAIN_TIME)
            LedgerLog.i("Prize", "下次定时核验：${delay} 分钟后（每天 ${MAIN_TIME}）")
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_MAIN,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PrizeCheckWorker>()
                    .setInitialDelay(delay, TimeUnit.MINUTES)
                    .build()
            )
        }

        /** 安排次日 08:30 的补偿核验。 */
        private fun scheduleRetry(context: Context) {
            val delay = minutesUntil(RETRY_TIME)
            LedgerLog.i("Prize", "官方还没出结果，安排 ${delay} 分钟后（${RETRY_TIME}）再试一次")
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_RETRY,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PrizeCheckWorker>()
                    .setInitialDelay(delay, TimeUnit.MINUTES)
                    .build()
            )
        }

        /**
         * 距离今天（或明天）的 [target] 还有多少分钟。
         *
         * [now] 可注入，方便单测 —— 时间计算是最容易算错的东西。
         */
        fun minutesUntil(target: LocalTime, now: LocalDateTime = LocalDateTime.now()): Long {
            var next = LocalDateTime.of(now.toLocalDate(), target)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return ChronoUnit.MINUTES.between(now, next).coerceAtLeast(1)
        }
    }

    override suspend fun doWork(): Result {
        LedgerLog.i("Prize", "定时核验开始（workId=$id）")
        return try {
            val service = PrizeService(applicationContext)
            val outcomes = service.checkAllPending()

            // 没有待核验的票 → 直接跳过，不白跑（少爷的明确要求）
            if (outcomes.isEmpty()) {
                LedgerLog.i("Prize", "没有待核验的票，本轮跳过")
            } else {
                val won = outcomes.count { it.second is PrizeService.Outcome.Won }
                val awaiting = outcomes.count { it.second is PrizeService.Outcome.AwaitingResult }
                val failed = outcomes.count { it.second is PrizeService.Outcome.Failed }
                LedgerLog.i(
                    "Prize",
                    "定时核验完成：共 ${outcomes.size} 张，中奖 $won，待官方公布 $awaiting，失败 $failed"
                )

                // 官方还没出结果 → 次日早上再试一次
                if (awaiting > 0) scheduleRetry(applicationContext)
            }

            // 顺手扫一遍「已过兑奖截止日但还没兑」的票。
            // 注意放在 if/else **外面**：这类票状态是 TO_REDEEM，不在 needsCheck 的范围内，
            // 所以单看 outcomes 是漏的。
            val expired = service.sweepExpired()
            if (expired > 0) {
                LedgerLog.w("Prize", "本轮标记了 $expired 张「已过期未兑奖」")
            }

            // 无论结果如何，都排下一天
            schedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            LedgerLog.e("Prize", "定时核验失败", e)
            // 失败也要重排，否则这个任务链就断了、以后再也不会跑
            schedule(applicationContext)
            Result.retry()
        }
    }
}
