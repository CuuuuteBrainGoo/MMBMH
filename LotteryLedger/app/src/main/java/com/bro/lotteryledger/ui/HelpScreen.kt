package com.bro.lotteryledger.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.bro.lotteryledger.core.AiProviderKind
import com.bro.lotteryledger.core.ProviderDefaults

/**
 * 帮助页 —— 给「从没配过 AI 平台」的人看的。
 *
 * 少爷 2026-09-27 第 3 条要求：
 *  把 AI 选择 + 填写说明写清楚，并且**能一键跳到各平台官网和官方手册**。
 *
 * 设计原则（跟全站一致：先给结论，再给过程）：
 *  1. 开头三句话把「这是干嘛的、要不要花钱、数据去哪」说完 —— 这是新用户最大的三个疑虑
 *  2. 然后给出「选哪家」的建议（带推荐项，不甩一堆平台让人自己挑）
 *  3. 最后才是六个平台的卡片：官网 + 手册 + 去哪抄三个值
 *
 * 为什么每个平台要同时给「官网」和「手册」两个链接：
 *  官网是去**注册/充值/拿 Key** 的地方，手册是**查模型名和地址**的地方，
 *  两件事发生在不同页面，新用户常常找不到手册在哪，索性都直接给。
 *
 * 链接统一走系统浏览器（`Intent.ACTION_VIEW`），**不在 App 内开 WebView** ——
 * 这些站点都要登录，塞进 App 里反而更难用，也省得担 WebView 的安全问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current

    /** 用系统浏览器打开链接。打不开（没浏览器）就静默忽略，不弹错误打扰用户。 */
    fun open(url: String) {
        if (url.isBlank()) return
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 配置帮助") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- 1. 先回答新用户最关心的三个问题 ----
            item {
                SectionCard(title = "这是干什么的？") {
                    Paragraph(
                        "这个 App 用 AI 帮你「读彩票照片」，把号码自动认出来，你就不用一个个手敲。" +
                            "AI 只负责读号码 —— 中没中奖是 App 自己在你手机上算的，跟 AI 无关，也不联网。"
                    )
                    Bullet("要不要花钱：", "要。AI 是按量计费的，但读一张票大概一两分钱。" +
                        "六家里有免费额度可以先试（见下方推荐）。")
                    Bullet("我的数据去哪了：", "只有「那一张票的照片」会发给 AI 平台。" +
                        "账本记录、金额、你的身份信息，一个字节都不会离开这台手机。")
                    Bullet("照片会存下来吗：", "不会。识别完立即删除，App 里也找不到历史照片。")
                }
            }

            // ---- 2. 选哪家 ----
            item {
                SectionCard(title = "第一次配，选哪家？") {
                    Paragraph(
                        "六家都能用，配置方式完全一样，配错了随时换、账本不受影响。" +
                            "如果拿不定主意，按下面两条走："
                    )
                    RecommendLine(
                        "想先零成本试通 —— 智谱的 glm-4v-flash",
                        "免费、注册简单，够把「拍照识别」这条路走通。缺点是高峰期偶尔慢。"
                    )
                    RecommendLine(
                        "想一步到位、识别更准 —— 豆包 / 火山方舟",
                        "识别质量最好，就是这个 App 一直用的那家。要实名 + 充值，" +
                            "并且**必须先在控制台创建「推理接入点」**（这是最容易踩的坑，下方有专门说明）。"
                    )
                }
            }

            // ---- 3. 三个格子分别填什么 ----
            item {
                SectionCard(title = "三个格子分别填什么") {
                    Paragraph("添加平台时要填三样东西，都能在平台控制台里找到：")
                    NumberedStep(
                        "接口地址",
                        "控制台给的「Base URL」，一般以 /v1 或 /api/v3 结尾。" +
                            "选中平台后 App 会自动填好，通常不用改。"
                    )
                    NumberedStep(
                        "模型名",
                        "要选**能看图**的模型（名字里常带 v / vision / vl）。" +
                            "纯文字模型读不了照片，会报错。"
                    )
                    NumberedStep(
                        "API Key",
                        "平台的密钥，相当于你账号的钥匙。**只存在这台手机里**，" +
                            "用系统级加密保存，不会上传、也不会进备份文件。" +
                            "换手机或重装 App 后需要重新填一次。"
                    )
                    Paragraph(
                        "填完建议点一下「测试连通性」—— 它会真发一张图过去，" +
                            "通不通、错在哪一步，当场告诉你，比存下来再试要省事得多。"
                    )
                }
            }

            // ---- 4. 豆包的特殊坑，单独强调 ----
            item {
                SectionCard(title = "⚠️ 用豆包？先看这条", accent = LedgerColors.Warn) {
                    Paragraph(
                        "火山方舟**不能直接填模型名**，必须先在控制台创建「推理接入点」，" +
                            "把那个 ep- 开头的 ID 填进「模型名」这一格。"
                    )
                    Paragraph(
                        "流程：登录方舟控制台 → 左侧「在线推理」→「推理接入点」→ 创建 → " +
                            "选择一个视觉模型（如 doubao-seed 系列）→ 创建好后复制那串 ep- 开头的 ID。"
                    )
                    Paragraph(
                        "如果填了模型名，接口会返回 404 说「model or endpoint does not exist」——" +
                            "这个提示很容易被误读成「地址填错了」，其实是「该填接入点 ID」。"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            open(ProviderDefaults.template(AiProviderKind.DOUBAO).consoleUrl)
                        }) {
                            Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("打开方舟控制台")
                        }
                    }
                }
            }

            // ---- 5. 各平台跳转 ----
            item {
                Spacer(Modifier.height(4.dp))
                Text("各平台官网与官方手册", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点「官网」去注册和拿 Key，点「手册」去查模型名和接口地址。" +
                        "链接会用系统浏览器打开，方便你登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(ProviderDefaults.documentedKinds) { kind ->
                val t = ProviderDefaults.template(kind)
                PlatformCard(
                    kind = kind,
                    baseUrl = t.baseUrl,
                    model = t.model,
                    onConsole = { open(t.consoleUrl) },
                    onDocs = { open(t.docsUrl) }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "以上链接均为各平台官方域名。App 与这些平台没有合作关系，" +
                        "只是按 OpenAI 兼容协议调用，用哪家、用不用都由你定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 一个带标题的白/灰底卡片，用来分组。 */
@Composable
private fun SectionCard(
    title: String,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent ?: MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 「黑体小标题：内容」的一行，用来讲清一件事。 */
@Composable
private fun Bullet(label: String, text: String) {
    Row {
        Text(
            "· ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            buildString {
                append(label)
                append(text)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 推荐项：标题用主题色加粗，理由用灰字跟在下面。 */
@Composable
private fun RecommendLine(title: String, reason: String) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Check, null,
                Modifier.size(14.dp).padding(top = 2.dp),
                tint = LedgerColors.Spend
            )
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = LedgerColors.Spend
            )
        }
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp)
        )
    }
}

/** 编号步骤。 */
@Composable
private fun NumberedStep(name: String, desc: String) {
    Column {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/** 单个平台的跳转卡片：左边名字 + 抄什么，右边两个跳转按钮。 */
@Composable
private fun PlatformCard(
    kind: AiProviderKind,
    baseUrl: String,
    model: String,
    onConsole: () -> Unit,
    onDocs: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                kind.display,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (baseUrl.isNotBlank()) {
                Text(
                    "地址：$baseUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (model.isNotBlank()) {
                Text(
                    "模型：$model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onConsole, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("官网", style = MaterialTheme.typography.bodySmall)
                }
                if (kind != AiProviderKind.CUSTOM) {
                    OutlinedButton(onClick = onDocs, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("官方手册", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
