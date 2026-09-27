package com.bro.lotteryledger.core

/**
 * 主题模式（少爷 2026-09-27 第 6 条）。
 *
 * 之前 App 只认系统的深浅色设置，少爷手机跟着系统走 —— 但账本这类天天看的东西，
 * 白天想亮一点、晚上想暗一点，跟系统切来切去很烦。所以给他一个自选项。
 *
 * ## 三种取值
 *
 * - [LIGHT]  强制浅色（宣纸白底）
 * - [DARK]   强制深色（墨色）
 * - [SYSTEM] 跟随系统（**默认**）
 *
 * 默认选 [SYSTEM] 而不是 [LIGHT]：老用户升级上来时，行为**跟以前完全一致**，
 * 不会因为加了设置项就突然把晚上看惯的深色变成刺眼的白色。
 */
enum class ThemeMode(val code: String, val display: String, val hint: String) {
    SYSTEM(
        code = "system",
        display = "跟随系统",
        hint = "白天浅色、晚上深色，跟手机的显示设置一起变。默认就是这个。"
    ),
    LIGHT(
        code = "light",
        display = "浅色",
        hint = "一直用宣纸白底，白天看得清。适合在户外或亮光下看。"
    ),
    DARK(
        code = "dark",
        display = "深色",
        hint = "一直用墨色底，晚上看不刺眼。适合睡前翻账本。"
    );

    companion object {
        /** 从存储里读出的字符串还原；认不出来就回落 [SYSTEM]。 */
        fun from(code: String?): ThemeMode =
            entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}
