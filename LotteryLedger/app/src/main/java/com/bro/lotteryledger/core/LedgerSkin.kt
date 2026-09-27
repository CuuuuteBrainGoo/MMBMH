package com.bro.lotteryledger.core

/**
 * 皮肤（配色方案）。少爷 2026-09-27 要求做「支付宝」和「微信」两套。
 *
 * ## 皮肤和深浅色是两个维度
 *
 * 很多人会把它俩混成一个「主题」列表，但它们是**正交**的：
 *
 * |  | 浅色 | 深色 |
 * |---|---|---|
 * | 墨金账本 | 宣纸白 | 墨夜 |
 * | 支付宝 | 冷白 | 近黑 |
 * | 微信 | 微信灰 | 暗灰 |
 *
 * 所以设置页里是**两组选项**：先选皮肤，再选深浅色。3 × 3 = 9 种实际外观，
 * 但用户只需要做两个决定。混成一个 9 项列表是设计失误 —— 用户会找不到「我想要暗的」。
 *
 * ## 默认必须是 [MOJIN] 而不是第一项「支付宝」
 *
 * 老用户升级上来时，看到的必须还是原来那套国风配色。
 * 新增一个枚举项就把所有人的界面改掉，是**最不该犯的错**。
 * （同样的道理见 [ThemeMode]，默认也是 [ThemeMode.SYSTEM]。）
 */
enum class LedgerSkin(val code: String, val display: String, val hint: String) {
    MOJIN(
        code = "mojin",
        display = "墨金账本",
        hint = "宣纸白底 + 墨绿主色 + 中国红点缀，跟 App 图标的水墨画一套。默认就是这个。"
    ),
    ALIPAY(
        code = "alipay",
        display = "支付宝",
        hint = "冷白底 + 支付宝蓝。卡片是纯白带细边，界面更「工具」一点。"
    ),
    WECHAT(
        code = "wechat",
        display = "微信",
        hint = "微信灰底 + 微信绿。整块更素，长时间看不累。"
    );

    companion object {
        /**
         * 从存储里读出的字符串还原；认不出来就回落 [MOJIN]。
         *
         * 回落必须是 [MOJIN] 而不是 `entries.first()` —— 否则以后往枚举**前面**
         * 插新皮肤时，老用户的设置会静默变成那个新皮肤。
         */
        fun from(code: String?): LedgerSkin =
            entries.firstOrNull { it.code == code } ?: MOJIN
    }
}
