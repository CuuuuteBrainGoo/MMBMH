package com.bro.lotteryledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bro.lotteryledger.core.LedgerSkin

/**
 * 配色主题 = **皮肤 × 深浅色**（少爷 2026-09-27 要求加皮肤）。
 *
 * ## 两个维度是正交的
 *
 * 皮肤（[LedgerSkin]）决定「这是哪一家 App 的观感」，
 * 深浅色（[ThemeMode]）决定「白天还是晚上」。3 × 2 = 6 套实际外观：
 *
 * |  | 浅色 | 深色 |
 * |---|---|---|
 * | 墨金账本 | 宣纸白 + 墨绿 | 墨夜 + 暖灰卡纸 |
 * | 支付宝 | 冷白 + 支付宝蓝 | 近黑 + 深灰卡 |
 * | 微信 | 微信灰 + 微信绿 | 暗灰 + 略亮卡 |
 *
 * 合并成一个 6 项列表是设计失误 —— 用户想「换个暗的」时会找不到。
 *
 * ## 三套皮肤都必须守住的硬约束
 *
 * 1. **中奖永远是红**（中国习惯，红 = 涨）。皮肤不许改。
 * 2. **过期未兑永远是紫**；**号码球永远是红球红、蓝球蓝**（彩票规则，不是审美）。
 * 3. **深色不是把浅色反转**：浅色靠「比背景深」分层，深色靠「比背景亮」分层。
 *    所以三套深色配色都是重新配的，不是简单调暗。
 *
 * ## 语义色必须跟着主题走（原来不是）
 *
 * 之前 [LedgerColors] 里全是**写死的常量** —— 于是深色模式下中奖红、支出墨绿
 * 都是照浅色底配的，放到近黑的背景上「沉进背景」看不清。
 * 现在拆成 light / dark 两组，由 [LedgerColors] 这个 `object` 转发当前值。
 */
data class LedgerPalette(
    /** 中奖 / 正向（**永远是红**，中国习惯） */
    val prize: Color,
    /**
     * 支出 / 负向。
     *
     * ⚠️ 墨金用墨绿，支付宝和微信用**灰** —— 因为这两套的主色分别是蓝和绿，
     * 支出再留绿的话在微信皮肤下「支出绿」和「主色绿」会撞在一起，
     * 用户分不清哪个是按钮、哪个是"我亏了"。灰 = 钱花掉了，语义上完全说得通。
     */
    val spend: Color,
    /** 待确认 / 需要留意（金） */
    val warn: Color,
    /** 失效 / 中性 */
    val muted: Color,
    /** 过期未兑奖（**永远是紫**） */
    val expired: Color,
    val ballRed: Color,
    val ballBlue: Color,
    /** 强调数字、装饰用的金。 */
    val gold: Color,
    /** 居中提示条的底（**故意不跟深浅色走**，两种主题下都要浮在最上面） */
    val noticeNeutral: Color,
    /** 居中提示条的字 */
    val noticeText: Color,
    /** 操作失败的底 —— 暗橙，不是红（红专供「中奖」） */
    val noticeError: Color,
    /** 「号码撞大奖」彩蛋紫。跟业务色全错开，因为它是玩笑不是状态 */
    val tease: Color,
    /**
     * 顶栏图标按钮的底片填充（少爷 2026-09-27 定的「方案 2」）。
     *
     * ⚠️ 必须**极淡**：它是"托一下图标"用的，不是色块。实心色块已经被少爷
     * 以「太突兀」否过一次（首页墨金黑块那次），这里同理。
     */
    val topBarChip: Color,
    /** 顶栏图标本身的颜色。比正文略沉一点，但比原来的 onSurfaceVariant 亮得多。 */
    val topBarIcon: Color,
    /**
     * 设置页里给 App 图标垫的底。
     *
     * **故意不跟皮肤走**：图标是墨金风格的水墨画，垫一块宣纸米色才对味，
     * 换成支付宝蓝 / 微信绿反而跟图标打架。但**必须跟深浅色走** ——
     * 近黑背景上戳一块亮米色会很刺眼，所以深色下要换成暗褐。
     */
    val iconPad: Color
)

// ---------------- 墨金账本（默认，也是老用户原来的观感） ----------------

/**
 * 墨金账本 = App 图标的气质：**宣纸白打底、墨绿做骨架、中国红点睛、金作点缀**。
 * 图标是「关公捧金元宝」的水墨画，所以配色走国风。之前用 Material 默认蓝，
 * 跟图标放一起是两个世界。
 */
private val MojinLight = LedgerPalette(
    prize = Color(0xFFC0392B),
    spend = Color(0xFF2E7D5B),
    warn = Color(0xFFC08A2E),
    muted = Color(0xFF8A8172),
    expired = Color(0xFF7B3FA0),
    ballRed = Color(0xFFC0392B),
    ballBlue = Color(0xFF2F6BA8),
    gold = Color(0xFFC08A2E),
    noticeNeutral = Color(0xFF322F2A),
    noticeText = Color(0xFFF2EDE3),
    noticeError = Color(0xFF9C4A22),
    tease = Color(0xFF5B4B8A),
    // 浅色靠"比背景深"分层 → 底片是**极淡的黑**
    topBarChip = Color(0x0E1C1A17),
    topBarIcon = Color(0xFF2A2723),
    iconPad = Color(0xFFF2EBDC)
)

/**
 * 墨金 · 深色。
 *
 * 深色下语义色要**整组提亮**：亮的底上「深红」够看，近黑的底上同一条红就糊了。
 * 且深色下不能靠"比背景深"分层，只能靠"比背景亮" —— 所以卡片、主账都比背景亮。
 */
private val MojinDark = LedgerPalette(
    prize = Color(0xFFE0503F),
    spend = Color(0xFF4FA47C),
    warn = Color(0xFFE0B455),
    muted = Color(0xFF9A9288),
    expired = Color(0xFFB98AE0),
    ballRed = Color(0xFFE0503F),
    // 号码球的蓝球跟支付宝主色撞了也不改 —— 这是彩票规则，深一点把两者拉开
    ballBlue = Color(0xFF4E8AE0),
    gold = Color(0xFFE0B455),
    noticeNeutral = Color(0xFF322F2A),
    noticeText = Color(0xFFF2EDE3),
    noticeError = Color(0xFFB85A2E),
    tease = Color(0xFF8B7AC0),
    // 深色靠"比背景亮"分层 → 底片是**极淡的白**
    topBarChip = Color(0x17EDE7DC),
    topBarIcon = Color(0xFFE8E2D8),
    iconPad = Color(0xFF3A342B)
)

// ---------------- 支付宝皮肤 ----------------

private val AlipayLight = LedgerPalette(
    // 中奖永远是红：#F5222D 是支付宝体系里的正红，跟"涨"的中国习惯一致
    prize = Color(0xFFF5222D),
    // 支出改灰 —— 主色是蓝，留绿会跟蓝抢戏，灰更符合"钱花掉了"
    spend = Color(0xFF6B7280),
    warn = Color(0xFFD48806),
    muted = Color(0xFF8A9099),
    expired = Color(0xFF7B3FA0),
    // 红球保留正红（彩票规则）；蓝球用**更深的蓝**跟主色 #1677FF 拉开
    ballRed = Color(0xFFF5222D),
    ballBlue = Color(0xFF0A4FB5),
    gold = Color(0xFFD48806),
    noticeNeutral = Color(0xFF2B2F36),
    noticeText = Color(0xFFF5F6F8),
    noticeError = Color(0xFF9C4A22),
    tease = Color(0xFF5B4B8A),
    topBarChip = Color(0x141677FF),
    topBarIcon = Color(0xFF1D2129),
    iconPad = Color(0xFFF2EBDC)
)

private val AlipayDark = LedgerPalette(
    prize = Color(0xFFFF6B63),
    // 深色下灰要提亮一档，否则在 #1E2226 的卡上分不出来
    spend = Color(0xFF9AA3AE),
    warn = Color(0xFFE8B04A),
    muted = Color(0xFF8B939D),
    expired = Color(0xFFB98AE0),
    ballRed = Color(0xFFFF6B63),
    // 深色下主色翻到 #5AA0FF（更亮），所以蓝球反而要用**更暗**的蓝
    ballBlue = Color(0xFF3D7BC8),
    gold = Color(0xFFE8B04A),
    noticeNeutral = Color(0xFF2B2F36),
    noticeText = Color(0xFFF5F6F8),
    noticeError = Color(0xFFB85A2E),
    tease = Color(0xFF8B7AC0),
    topBarChip = Color(0x1A5AA0FF),
    topBarIcon = Color(0xFFE6EAF0),
    iconPad = Color(0xFF3A342B)
)

// ---------------- 微信皮肤 ----------------

private val WechatLight = LedgerPalette(
    prize = Color(0xFFF5222D),
    // 主色就是绿 #07C160，支出必须让开 → 灰
    spend = Color(0xFF8A8A8A),
    warn = Color(0xFFD48806),
    muted = Color(0xFF9A9A9A),
    expired = Color(0xFF7B3FA0),
    ballRed = Color(0xFFF5222D),
    ballBlue = Color(0xFF2F6BA8),
    gold = Color(0xFFD48806),
    noticeNeutral = Color(0xFF2C2C2C),
    noticeText = Color(0xFFEDEDED),
    noticeError = Color(0xFF9C4A22),
    // 彩蛋紫跟主色绿错开得最远，保留
    tease = Color(0xFF5B4B8A),
    topBarChip = Color(0x1407C160),
    topBarIcon = Color(0xFF1F1F1F),
    iconPad = Color(0xFFF2EBDC)
)

private val WechatDark = LedgerPalette(
    prize = Color(0xFFFF6B63),
    spend = Color(0xFF9E9E9E),
    warn = Color(0xFFE8B04A),
    muted = Color(0xFF8C8C8C),
    expired = Color(0xFFB98AE0),
    ballRed = Color(0xFFFF6B63),
    ballBlue = Color(0xFF4E8AE0),
    gold = Color(0xFFE8B04A),
    noticeNeutral = Color(0xFF2C2C2C),
    noticeText = Color(0xFFEDEDED),
    noticeError = Color(0xFFB85A2E),
    tease = Color(0xFF8B7AC0),
    topBarChip = Color(0x1A07C160),
    topBarIcon = Color(0xFFE4E4E4),
    iconPad = Color(0xFF3A342B)
)

/**
 * 当前皮肤 + 深浅色对应的语义色组。
 *
 * 调用点写 `LedgerColors.Prize` 就够了 —— 内部转发到当前色组，
 * **界面上所有调用一行都不用改**，自动跟皮肤和深浅色走。
 *
 * 用 `@ReadOnlyComposable` 是因为它只读 [LocalLedgerPalette]，
 * 不产生任何状态写入；这样组合期不会有额外重组开销。
 */
object LedgerColors {
    private val p: LedgerPalette
        @Composable @ReadOnlyComposable get() = LocalLedgerPalette.current

    val Prize: Color @Composable @ReadOnlyComposable get() = p.prize
    val Spend: Color @Composable @ReadOnlyComposable get() = p.spend
    val Warn: Color @Composable @ReadOnlyComposable get() = p.warn
    val Muted: Color @Composable @ReadOnlyComposable get() = p.muted
    val Expired: Color @Composable @ReadOnlyComposable get() = p.expired
    val BallRed: Color @Composable @ReadOnlyComposable get() = p.ballRed
    val BallBlue: Color @Composable @ReadOnlyComposable get() = p.ballBlue
    val Gold: Color @Composable @ReadOnlyComposable get() = p.gold
    val NoticeNeutral: Color @Composable @ReadOnlyComposable get() = p.noticeNeutral
    val NoticeText: Color @Composable @ReadOnlyComposable get() = p.noticeText
    val NoticeError: Color @Composable @ReadOnlyComposable get() = p.noticeError
    val Tease: Color @Composable @ReadOnlyComposable get() = p.tease
    /** 顶栏图标按钮的底片填充（方案 2） */
    val TopBarChip: Color @Composable @ReadOnlyComposable get() = p.topBarChip
    /** 顶栏图标本身的颜色（方案 2） */
    val TopBarIcon: Color @Composable @ReadOnlyComposable get() = p.topBarIcon
    /** 设置页里给 App 图标垫的底（跟深浅色走、不跟皮肤走） */
    val IconPad: Color @Composable @ReadOnlyComposable get() = p.iconPad
}

/**
 * 当前语义色组。`static` 是因为**换皮肤必须重建整棵树** ——
 * 用普通 `compositionLocalOf` 会让一部分节点用旧色、一部分用新色，
 * 切换瞬间出现"半新半旧"的闪屏。
 */
private val LocalLedgerPalette = staticCompositionLocalOf { MojinLight }

/**
 * 拿当前色组。给需要整组传下去的地方用（比如把一组色递给图表组件）。
 */
object LedgerTheme {
    val palette: LedgerPalette
        @Composable @ReadOnlyComposable get() = LocalLedgerPalette.current
}

// ---------------- Material 表面色 ----------------

/**
 * 主色系也跟皮肤走 —— 否则「支付宝皮肤 + 墨绿按钮」会是个笑话。
 *
 * 只列出**各皮肤真正不同**的那几项，其余交给 [lightColorScheme] 的默认值。
 * 写满 30 个字段会让「换皮肤时到底该改哪几个」变得看不出来。
 */
private fun mojinLightScheme() = lightColorScheme(
    primary = Color(0xFF1F4A3C),             // 墨绿
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E5DC),
    onPrimaryContainer = Color(0xFF06271C),
    secondary = Color(0xFFA8352C),           // 中国红
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF8DAD6),
    onSecondaryContainer = Color(0xFF3F0A05),
    tertiary = Color(0xFFA67C1F),            // 金
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E6BF),
    onTertiaryContainer = Color(0xFF332600),
    background = Color(0xFFF7F3EA),          // 宣纸白
    onBackground = Color(0xFF1C1A16),
    surface = Color(0xFFFFFCF6),
    onSurface = Color(0xFF1C1A16),
    surfaceVariant = Color(0xFFEDE6D8),
    onSurfaceVariant = Color(0xFF5C564B),
    outline = Color(0xFFCFC7B6),
    error = Color(0xFFB3261E)
)

private fun mojinDarkScheme() = darkColorScheme(
    primary = Color(0xFF9CCFB9),
    onPrimary = Color(0xFF0A2A1F),
    primaryContainer = Color(0xFF22493A),
    onPrimaryContainer = Color(0xFFCDE8DA),
    secondary = Color(0xFFF0A79F),
    onSecondary = Color(0xFF4A1108),
    secondaryContainer = Color(0xFF5C211A),
    onSecondaryContainer = Color(0xFFFBDAD5),
    tertiary = Color(0xFFE0C070),
    onTertiary = Color(0xFF3B2E06),
    tertiaryContainer = Color(0xFF4A3A10),
    onTertiaryContainer = Color(0xFFF6E6BF),
    background = Color(0xFF14120F),          // 墨色
    onBackground = Color(0xFFEAE4D8),
    surface = Color(0xFF1C1A17),
    onSurface = Color(0xFFEAE4D8),
    surfaceVariant = Color(0xFF2A2722),
    onSurfaceVariant = Color(0xFFB8B0A0),
    outline = Color(0xFF453F36),
    error = Color(0xFFFFB4AB)
)

private fun alipayLightScheme() = lightColorScheme(
    primary = Color(0xFF1677FF),             // 支付宝蓝
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E6FF),
    onPrimaryContainer = Color(0xFF04275C),
    secondary = Color(0xFFF5222D),           // 正红
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD8),
    onSecondaryContainer = Color(0xFF3F0A08),
    tertiary = Color(0xFFD48806),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFECCB),
    onTertiaryContainer = Color(0xFF3A2900),
    background = Color(0xFFF5F6F8),          // 冷白
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF8A9099),
    outline = Color(0xFFE6E9EF),
    error = Color(0xFFD93026)
)

private fun alipayDarkScheme() = darkColorScheme(
    primary = Color(0xFF5AA0FF),
    onPrimary = Color(0xFF00244F),
    primaryContainer = Color(0xFF0F3D75),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFFFF897E),
    onSecondary = Color(0xFF5C0A05),
    secondaryContainer = Color(0xFF7A1F18),
    onSecondaryContainer = Color(0xFFFFDAD8),
    tertiary = Color(0xFFE8B04A),
    onTertiary = Color(0xFF3A2900),
    tertiaryContainer = Color(0xFF54400F),
    onTertiaryContainer = Color(0xFFFFECCB),
    background = Color(0xFF121417),          // 近黑（带一点冷调）
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1E2226),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF272C31),
    onSurfaceVariant = Color(0xFF9AA3AE),
    outline = Color(0xFF3A4046),
    error = Color(0xFFFFB4AB)
)

private fun wechatLightScheme() = lightColorScheme(
    primary = Color(0xFF07C160),             // 微信绿
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3F5E1),
    onPrimaryContainer = Color(0xFF00381B),
    secondary = Color(0xFFF5222D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD8),
    onSecondaryContainer = Color(0xFF3F0A08),
    tertiary = Color(0xFFD48806),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFECCB),
    onTertiaryContainer = Color(0xFF3A2900),
    background = Color(0xFFEDEDED),          // 微信灰
    onBackground = Color(0xFF171717),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFFE3E3E3),
    error = Color(0xFFD93026)
)

private fun wechatDarkScheme() = darkColorScheme(
    primary = Color(0xFF3ED584),
    onPrimary = Color(0xFF00381B),
    primaryContainer = Color(0xFF0B5330),
    onPrimaryContainer = Color(0xFFD3F5E1),
    secondary = Color(0xFFFF897E),
    onSecondary = Color(0xFF5C0A05),
    secondaryContainer = Color(0xFF7A1F18),
    onSecondaryContainer = Color(0xFFFFDAD8),
    tertiary = Color(0xFFE8B04A),
    onTertiary = Color(0xFF3A2900),
    tertiaryContainer = Color(0xFF54400F),
    onTertiaryContainer = Color(0xFFFFECCB),
    background = Color(0xFF191919),          // 暗灰（微信深色就是这个调）
    onBackground = Color(0xFFD8D8D8),
    surface = Color(0xFF252525),
    onSurface = Color(0xFFD8D8D8),
    surfaceVariant = Color(0xFF2E2E2E),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF3D3D3D),
    error = Color(0xFFFFB4AB)
)

/**
 * 皮肤 × 深浅色 → Material 表面配色。
 *
 * `when` 写成**两层各自的 when**，加第四套皮肤时只需要在 [LedgerSkin] 加一项，
 * 编译器会在两个 `when` 上都报「not exhaustive」—— 漏配一套会被编译拦住。
 */
private fun schemeFor(skin: LedgerSkin, dark: Boolean): ColorScheme = when (skin) {
    LedgerSkin.MOJIN -> if (dark) mojinDarkScheme() else mojinLightScheme()
    LedgerSkin.ALIPAY -> if (dark) alipayDarkScheme() else alipayLightScheme()
    LedgerSkin.WECHAT -> if (dark) wechatDarkScheme() else wechatLightScheme()
}

private fun paletteFor(skin: LedgerSkin, dark: Boolean): LedgerPalette = when (skin) {
    LedgerSkin.MOJIN -> if (dark) MojinDark else MojinLight
    LedgerSkin.ALIPAY -> if (dark) AlipayDark else AlipayLight
    LedgerSkin.WECHAT -> if (dark) WechatDark else WechatLight
}

/**
 * 字号刻意拉开层差：数字要能"跳出来"，说明文字要能"退下去"。
 * 之前所有字号都挤在 12~17sp，整页看起来就是一片灰。
 */
private val LedgerTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
)

/** 统一的圆角语言：大卡片更圆、chip 更方，形成层次。 */
private val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun LedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    skin: LedgerSkin = LedgerSkin.MOJIN,
    content: @Composable () -> Unit
) {
    // 语义色组通过 CompositionLocal 往下传 —— 这样 [LedgerColors] 可以保持
    // 原来的 `LedgerColors.Prize` 写法不变，所有调用点一行都不用改
    CompositionLocalProvider(LocalLedgerPalette provides paletteFor(skin, darkTheme)) {
        MaterialTheme(
            colorScheme = schemeFor(skin, darkTheme),
            typography = LedgerTypography,
            shapes = LedgerShapes,
            content = content
        )
    }
}
