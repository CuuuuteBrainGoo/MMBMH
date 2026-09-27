package com.bro.lotteryledger.repo

import android.content.Context
import com.bro.lotteryledger.db.AppSettingEntity
import com.bro.lotteryledger.db.LedgerDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 零散设置的读写（落在 `app_settings` 这张现成的 key-value 表上）。
 *
 * 为什么不给 `ai_providers` 加字段：那要改 DB version + 写 Migration，
 * 而少爷手机上**已经有真实账目**（双色球、大乐透各一张）。
 * 迁移一旦写错就是账目全丢。这张表本来就是给这类设置准备的，用它零风险。
 *
 * 读取失败一律回落到默认值：设置读不出来不该让主流程崩。
 */
class SettingsStore(context: Context) {

    private val dao = LedgerDb.get(context).appSettingDao()

    suspend fun getBoolean(key: String, default: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val v = dao.get(key) ?: return@withContext default
            v == "1" || v.equals("true", ignoreCase = true)
        } catch (_: Exception) {
            default
        }
    }

    suspend fun putBoolean(key: String, value: Boolean) = withContext(Dispatchers.IO) {
        try {
            dao.put(AppSettingEntity(key, if (value) "1" else "0"))
        } catch (_: Exception) {
            // 存不进去不影响本次会话（内存里已经生效），下次启动回落默认值
        }
    }

    suspend fun getString(key: String, default: String): String = withContext(Dispatchers.IO) {
        try {
            dao.get(key)?.takeIf { it.isNotBlank() } ?: default
        } catch (_: Exception) {
            default
        }
    }

    suspend fun putString(key: String, value: String) = withContext(Dispatchers.IO) {
        try {
            dao.put(AppSettingEntity(key, value))
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * 思考强度：`fast` / `balanced` / `deep`，对应 [com.bro.lotteryledger.core.ThinkingLevel]。
         *
         * 默认 `fast` —— 实测同一张票面：快速档 3.4 秒，而平台默认的深度档 23.9 秒（慢 7 倍），
         * 且两者输出正文长度几乎一致（510 vs 508 字）。默认当然选快的那个。
         */
        const val KEY_THINKING_LEVEL = "thinking_level"

        /**
         * 主题模式：`system` / `light` / `dark`，对应 [com.bro.lotteryledger.core.ThemeMode]。
         *
         * 默认 `system` —— 老用户升级上来行为跟以前完全一致（以前就是纯跟随系统）。
         */
        const val KEY_THEME_MODE = "theme_mode"

        /**
         * 皮肤：`mojin` / `alipay` / `wechat`，对应 [com.bro.lotteryledger.core.LedgerSkin]。
         *
         * 默认 `mojin` —— 少爷 2026-09-27 要求的。**绝不能**因为往枚举首位插了
         * 一个「支付宝」就让所有老用户的界面变样，所以还原时的回落写死在墨金上。
         *
         * 皮肤和深浅色是**两个正交维度**，各存一个 key；合起来才是最终外观。
         */
        const val KEY_SKIN = "skin"
    }
}
