# 彩票账本 · LotteryLedger

[![最新版本](https://img.shields.io/github/v/release/CuuuuteBrainGoo/MMBMH?label=最新版本)](https://github.com/CuuuuteBrainGoo/MMBMH/releases/latest)

一款 Android 彩票记账应用，支持双色球和大乐透。拍照或从相册导入彩票，由你配置的视觉模型读取票面，再由 App 本地校验、对奖和记账。也支持手工录入。

## 下载安装

需要 Android 8.0 或更高版本。

| 安装包 | 用途 |
| --- | --- |
| [LotteryLedger-v1.7.0-release-signed.apk](https://github.com/CuuuuteBrainGoo/MMBMH/releases/download/v1.7.0/LotteryLedger-v1.7.0-release-signed.apk) | 日常使用，约 14 MB，推荐 |
| [LotteryLedger-v1.7.0-debug-signed.apk](https://github.com/CuuuuteBrainGoo/MMBMH/releases/download/v1.7.0/LotteryLedger-v1.7.0-debug-signed.apk) | 调试排查，约 21 MB |

所有已发布版本见 [Releases](https://github.com/CuuuuteBrainGoo/MMBMH/releases)。已有旧版时可使用相同签名的新版覆盖安装，建议升级前在 App 中导出账本 JSON 备份。

## v1.7.0 更新

- **AI 配置启用开关**：备用或额度用完的平台可暂时停用，保留配置和 API Key；编辑其他字段时保持原有开关状态。
- **快速档适配**：修正 DeepSeek 官方接口的关闭思考参数，适配火山 DeepSeek V4.1；始终思考的模型请求较轻档位。
- **耗时诊断**：记录图片准备、上传、等待响应、接收、重试次数和主识别/复核耗时；平台提供思考 token 用量时一并记录。
- **配置测试一致性**：连通性测试与正式识别使用相同的思考设置。

## 主要功能

- 拍照录入、相册导入、批量识别和手工填写。
- 票面号码、期号、金额等信息的本地校验；疑难结果可由第二个模型独立复核，冲突交由用户确认。
- 实体彩票去重；校验无异常且非重复的票可直接入账。
- 获取开奖结果后在本地对奖，支持兑奖状态、过期提示和一二等奖金额人工确认。
- 按彩种、期号等查看支出、奖金和净收支。
- JSON 备份导出与合并恢复。
- 墨金、支付宝、微信三套皮肤，以及浅色、深色和跟随系统模式。
- App 内配置帮助和可复制的诊断日志。

## 开始使用

1. 安装后进入“设置 → AI 识别平台”，添加支持图片输入的模型。
2. 填写服务商提供的接口地址、模型标识和自己的 API Key，先测试配置。
3. 将日常模型设为“主识别”并启用；其他备用配置可关闭。
4. 拍照或选择彩票图片，按提示核对结果后入账。

API Key 需自行向服务商申请。App 不附带可用密钥；各平台的免费额度、费用和服务限制以服务商控制台为准。手工录入不需要 AI Key。

多个主识别平台同时启用时，使用列表中最靠前的一个。模型调用失败时不会自动切换到付费备用配置，需要自行启用所需平台。停用开关作用于后续识别，已经发出的请求不会被撤回。

## 思考设置与识别速度

- 豆包 / 火山方舟和 DeepSeek 官方接口使用已适配的思考参数；其他兼容服务由其接口能力决定。
- 快速档请求关闭或减少思考。火山 DeepSeek V4.1 的 minimal 仍是轻量思考，本版对已识别的实际 Model ID 使用关闭参数。
- Kimi K3.1、GLM 5.3 等始终思考的模型只能调轻，不能保证关闭。
- 不透明的 ep- 接入点 ID 不包含底层模型信息；模型专属适配建议使用控制台调用示例中的实际 Model ID。
- 总耗时还受网络、图片、模型负载、重试和复核影响，没有固定秒数保证。连通测试使用内置小图，不能代替真实票照测速。

想比较模型速度，可在相同手机、网络和设置下使用相同票照，先核对准确性，再比较多次识别耗时。

## 数据与隐私

账本数据保存在本机。识别时，处理后的图片会发送到你所选的 AI 服务商；图片的外部处理适用该服务商的规则。App 不长期保存识别照片，从相册导入不会删除原图。

API Key 在本机加密保存，不进入账本备份，诊断日志不记录 Key 或图片 base64。更换设备时需要重新配置 Key。

AI 只负责读取票面，不判断中奖、不计算奖金。对奖和账目计算在本地完成；一二等奖金额需人工确认后计入盈亏。兑奖请保留实体票，并以彩票机构的实际核验为准。

## 源码与构建

工程使用 Kotlin、Jetpack Compose、Room 和 OkHttp，无独立后端。

- Android 工程：LotteryLedger/
- 核心规则与单元测试：LotteryLedger/app/src/
- 构建与校验脚本：tools/，使用方式见 [工具说明](tools/README.md)
- 版本号来源：LotteryLedger/version.properties

```properties
versionCode=17
versionName=1.7.0
```

本轮代码通过 500 项单元测试。安装包已验证版本信息、签名与新增功能代码；具体设备兼容性和真实服务耗时仍需安装后确认。
