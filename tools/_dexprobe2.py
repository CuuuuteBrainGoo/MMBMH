"""交付前拆 dex 验证：确认新代码真的打进 APK 了。"""
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _paths import APK_DIR, AAPT2, VERSION_FILE as _VP

# 占位值，下面会用 glob 覆盖成「apk 目录里最新的 debug 签名包」。
# 不写死版本号 —— 每次升版都要改脚本，迟早忘（2026-09-27 真发生过一次）。
APK = ""

# 不写死版本号 —— 每次升版都要改脚本，迟早忘（2026-09-27 就发生过一次）。
# 自动取 apk 目录里**最新**的 debug 签名包。
import glob as _glob
_cands = _glob.glob(os.path.join(APK_DIR, "LotteryLedger-*-debug-signed.apk"))
if _cands:
    APK = max(_cands, key=os.path.getmtime)

NEEDLES = {
    # ---- 本轮新增（v1.4.0：少爷 2026-09-27 第 1~4 条）----
    "期号归一化类": b"IssueNormalizer",
    "期号归一化日志": "期号已归一化".encode(),
    "编号归位类": b"IdentifierSplitter",
    "编号归位日志": "编号归位".encode(),
    "同期序号类": b"IssueSeq",
    "同期序号徽章": b"IssueSeqBadge",
    "彩蛋-调侃类": b"JackpotTease",
    "彩蛋-浮层": b"TeaseOverlay",
    "彩蛋-按钮": "扎心".encode(),
    "彩蛋-配文": "就差一期".encode(),
    "彩蛋-去重前缀": "jackpot_tease_seen_".encode(),
    "彩蛋-明确是玩笑": "这只是个玩笑".encode(),
    "彩蛋-注明票状态不变": "账本上的状态一个字都没变".encode(),
    # v1.4.0 顺带修的：界面文案里的 markdown 星号（Compose 不解析，会原样显示）
    "文案-中文引号强调": "「推理接入点 ID」".encode(),
    # ---- v1.6.0：彩票展示编号 + 首页滚动位置保留 ----
    "编号-核心类": b"TicketCode",
    "编号-计算入口": b"codeOf",
    "编号-卡片徽章": b"TicketCodeBadge",
    "编号-详情徽章": b"DetailCodeChip",
    "编号-详情行": "展示编号".encode(),
    "编号-重复票换号": b"duplicateReasonText",
    "滚动-列表state外提": b"homeListState",
    # ---- v1.6.2：开奖时刻判定收敛到唯一来源（2026-09-28「日期到了就说过期」bug）----
    # 这四个符号是本版修复的全部承载物：类 + 三个入口函数。
    # 只改一个文件就修好的 bug，最容易被「编译过了但包是旧的」骗过去。
    "开奖-口径类": b"DrawSchedule",
    "开奖-是否过点": b"drawTimePassed",
    "开奖-队列截止日": b"checkCutoff",
    "开奖-展示修正": b"effectiveStatus",
    "开奖-首页文案已改": "已过开奖时间".encode(),
    # ---- 上一轮（v1.3.2：大乐透多注 / 组合票）----
    "投注构成类": b"BetForm",
    "构成-从每注组合数": b"fromPerBetUnits",
    "构成-简短描述": b"labelCompact",
    "构成-单注形态判据": b"isMultiForm",
    "构成-组合票文案": "组合票 ".encode(),
    "构成-展开文案": "），展开 ".encode(),
    "构成-未填提示": " 注号码未填".encode(),
    "构成-全空文案": "号码还没填完".encode(),
    # ---- 上一轮（v1.3.1：单式多注误报修复 + 提示条居中配色）----
    "投注方式-复式判据": b"hasMultiBet",
    "投注方式-细节文案": b"firstMultiBetDetail",
    "投注方式-新提示语": "像是复式".encode(),
    "提示-类型枚举": b"NoticeKind",
    "提示-数据类": b"Notice",
    "提示-翻译层": b"PrizeNotice",
    "提示-居中组件": b"NoticeOverlay",
    "提示-底色映射": b"noticeBackground",
    "提示-中性底色": b"NoticeNeutral",
    "提示-失败底色": b"NoticeError",
    # ---- 上一批（v1.3.0 追加 4 条：过期票口径 + 交叉验证 + 公益贡献）----
    "交叉验证类": b"TicketCrossCheck",
    "交叉-反推注数": b"estimateUnits",
    "交叉-仲裁": b"arbitrate",
    "交叉-可抑制冲突": b"canSuppressConflict",
    "交叉-提示吻合": "与票面金额吻合".encode(),
    "公益-推算类": b"PublicWelfare",
    "公益-比例": b"RATIO",
    "公益-首页标题": "公益贡献 ".encode(),
    "公益-说明文案": "36% 估算".encode(),
    "过期-落点统一": b"landAfterWin",
    "过期-详情负号说明": "没能领到".encode(),
    "过期-列表减号": "过期 −".encode(),
    "过期-核验文案": "兑奖期就已经过了".encode(),
    # ---- 上一批（v1.3.0：拜拜财神 + 日志按周清理）----
    "清空-服务方法": b"clearLedger",
    "清空-票表": b"wipeAllTickets",
    "清空-注表": b"wipeAllBets",
    "清空-清点": b"ledgerCounts",
    "清空-结果类": b"WipeResult",
    "清空-入口文案": "拜拜财神，从头来过。".encode(),
    "清空-确认1标题": "真要跟财神说再见".encode(),
    "清空-确认2标题": "第二次确认".encode(),
    "清空-确认3标题": "最后一次了".encode(),
    "清空-取消文案": "手滑了，算了".encode(),
    "清空-继续文案": "想清楚了，继续".encode(),
    "清空-确认文案": "我知道，继续".encode(),
    "清空-最终按钮": "拜拜财神".encode(),
    "清空-完成提示": "账本已归零".encode(),
    "清空-连点工具": b"TapSequence",
    "清空-VM状态": b"WipeState",
    "清空-VM点击": b"tapWipeEntry",
    "清空-VM确认": b"confirmWipe",
    "日志-保留策略": b"LogRetention",
    "日志-判定函数": b"shouldSweep",
    "日志-周清理": b"sweepWeekly",
    "日志-基准文件": ".last_sweep".encode(),
    "日志-清理记录": "已按周清理日志".encode(),
    # 版本号不在这里查 —— 见文件末尾的说明（短版本号字符串会被第三方库命中的假阳性）
    # ---- 上一轮（v1.2.0：少爷提的 7 条）----
    "API Key 掩码类": b"ApiKeyMask",
    "掩码判定方法": b"isUnchangedMask",
    "冲突翻译类": b"ConflictText",
    "冲突人话-bets": "注数 / 投注内容".encode(),
    "本地预筛类": b"ImageGuard",
    "图片统计类": b"ImageStats",
    "预筛-纯色文案": "整片都是同一种颜色".encode(),
    "预筛-全黑文案": "几乎是全黑的".encode(),
    "新状态-查不到结果": b"RESULT_UNAVAILABLE",
    "新状态显示名": "查不到开奖结果".encode(),
    "查不到天数阈值": b"STALE_DAYS",
    "开奖查询-三分支": b"RequestFailed",
    "汇总文案函数": b"summarize",
    "一键兑奖服务": b"redeemAll",
    "一键兑奖按钮": "一键兑奖".encode(),
    "一键兑奖确认": "全部兑掉".encode(),
    "手动-标记未中奖": b"markNotWon",
    "手动-未中奖按钮文案": "我核对过了，没中奖".encode(),
    "状态色标统一": b"statusColor",
    "过期绿-状态文案": "已过期未兑奖".encode(),
    # ---- 前几轮的锚点（防止回归） ----
    "ConnectivityProbe": b"ConnectivityProbe",
    "FailureText": b"FailureText",
    # 用前缀 "ep-m-" 做锚点即可 —— 不要写真实接入点 ID！
    # （写真实 ID 会把少爷的账号资源固化进工具脚本，且可能被密钥扫描拦）
    "接入点前缀 (ep-)": b"ep-m-",
    "探针图尺寸常量": b"KNOWN_MIN_IMAGE_SIDE",
    "对奖引擎 PrizeRules": b"PrizeRules",
    "兑奖截止日": b"RedemptionDeadline",
    "开奖查询客户端": b"DrawResultClient",
    "定时核验 Worker": b"PrizeCheckWorker",
    "票详情页": b"TicketDetailScreen",
    "官方域名-福彩": b"cwl.gov.cn",
    "官方域名-体彩": b"sporttery.cn",
    # ---- 本轮新增：对奖闭环 ----
    "服务-标记已兑奖": b"markRedeemed",
    "服务-扫过期票": b"sweepExpired",
    "服务-确认金额": b"confirmAmount",
    "仓储-按状态取票": b"ticketsByStatus",
    "首页-一键核验按钮": "立即核验".encode(),
    "首页-核验中": "核验中…".encode(),
    "详情-标记已兑奖按钮": "标记已兑奖".encode(),
    "详情-兑奖确认框标题": "确认已兑奖？".encode(),
    "详情-确认金额按钮": "确认金额".encode(),
    "详情-金额输入标签": "中奖金额（元）".encode(),
    "详情-未到开奖日提示": "当天开奖时间之后才能核验".encode(),
    "详情-过期说明文案": "没能领到".encode(),
    "日志-过期标记": "已过兑奖截止日".encode(),
    "日志-定时扫过期": "本轮标记了".encode(),
    "toast-已确认奖金": "已确认奖金".encode(),
    "toast-已标记兑奖": "已标记兑奖".encode(),
    "VM-批量核验防重": b"_checking",
    # ---- 本轮新增：备份与恢复（§17）----
    "备份管理器": b"BackupManager",
    "备份编解码": b"BackupCodec",
    "备份格式标识": b"lottery-ledger-backup",
    "导出文件名前缀": "彩票账本备份-".encode(),
    "设置页-区块标题": "数据备份".encode(),
    "设置页-导出按钮": "导出备份".encode(),
    "设置页-恢复按钮": "恢复备份".encode(),
    "设置页-风险提示": "只存在这台手机里".encode(),
    "设置页-Key不进备份": "API Key 不进备份".encode(),
    "预览-标题": "恢复这个备份？".encode(),
    "预览-确认按钮": "开始恢复".encode(),
    "预览-无新票提示": "恢复不会带来任何变化".encode(),
    "处理中提示": "正在处理备份…".encode(),
    "错误-写入失败": "无法写入所选位置".encode(),
    "错误-读不到文件": "读不到这个文件".encode(),
    "错误-不是备份文件": "这不是「彩票账本」的备份文件".encode(),
    "错误-版本过新": "这份备份来自更新版本的 App".encode(),
    "日志-导出": "已导出：".encode(),
    "日志-恢复完成": "恢复完成：新增".encode(),
    "JSON-key-schemaVersion": b"schemaVersion",
    "JSON-key-ticketCount": b"ticketCount",
    # ---- 本轮新增：手工录入 + 复式/倍投 ----
    "注工具类 Bets": b"core/Bets",
    "单式复式判定": b"inferType",
    "日期自动补横线": b"autoDashDate",
    # 注：文案本轮由「不拍照，手工填写」缩短为「手工填写」——
    # 因为要和「批量导入照片」并排放在一行
    "入口-手工填写按钮": "手工填写".encode(),
    "手工页-引导条": "照票面逐项填写".encode(),
    "手工页-引导正文": "选得比单式需要多就是复式".encode(),
    "手工页-标题": "手工录入".encode(),
    "必填说明": "期号和开奖日期是对奖的必需信息".encode(),
    "日期占位提示": "敲 8 位数字自动补横线".encode(),
    "加注按钮": "添加一注".encode(),
    "删注无障碍文案": "删除第 ".encode(),
    "入口-未配平台提示": "拍照识别需先配 AI 平台".encode(),
    "日志-开始手工录入": "开始手工录入：".encode(),
    "录入方式-手工": "完全手工录入".encode(),
    # ---- 本轮新增：列表筛选与搜索 ----
    "筛选模型": b"TicketFilter",
    "状态筛选档": b"StatusFilter",
    "档位-已中奖": "已中奖".encode(),
    "档位-待开奖": "待开奖".encode(),
    "档位-待兑奖": "待兑奖".encode(),
    "档位-已过期": "已过期".encode(),
    "筛选-入口无障碍": "筛选与搜索".encode(),
    "筛选-输入框标签": "期号或票面编号".encode(),
    "筛选-统计不影响说明": "筛选只影响下面的列表；上方主账仍是全部账，".encode(),
    "筛选-清空关键词": "清空关键词".encode(),
    "筛选-清除全部": "清除全部筛选".encode(),
    "筛选-清除筛选": "清除筛选".encode(),
    "筛选-无结果标题": "没有符合条件的票".encode(),
    "筛选-无结果条件": "当前条件：".encode(),
    "筛选-关键词包装": "含「".encode(),
    # ---- 本轮新增：批量导入（§8）----
    "分诊模型": b"BatchTriage",
    "分诊结果枚举": b"DraftTriage",
    "仓储-队首草稿": b"oldestDraft",
    "入口-批量导入按钮": "批量导入照片".encode(),
    "进度-标题": "正在批量识别".encode(),
    "进度-张数格式": " 张".encode(),
    "进度-取消按钮": "取消（保留已识别的）".encode(),
    "进度-不丢票说明": "已识别的票会留着等你核对".encode(),
    "结果-取消标题": "已取消批量识别".encode(),
    "结果-全失败标题": "这批都没识别出来".encode(),
    "结果-完成标题": "批量识别完成".encode(),
    "结果-分类-可入账": "可以直接入账 ".encode(),
    "结果-分类-待确认": "需要你确认 ".encode(),
    "结果-分类-重复": "好像重复 ".encode(),
    "结果-按钮-直接入账": "直接入账 ".encode(),
    "结果-按钮-逐张核对": "逐张核对".encode(),
    "结果-全干净说明": "这一批都没问题，直接入账就行。".encode(),
    "首页-继续核对": "继续核对".encode(),
    "首页-草稿提示": "张识别好的票还没核对入账".encode(),
    "队列-空了": "没有待核对的票了".encode(),
    "队列-完了": "这批票都核对完了".encode(),
    "日志-批量开始": "开始批量识别 ".encode(),
    "日志-批量结束": "批量识别结束：成功 ".encode(),
    "日志-自动入账完成": "直接入账完成：入账 ".encode(),
    "日志-分诊": "分诊 → 可直接入账 ".encode(),
    "toast-已直接入账": "已直接入账 ".encode(),
    # ---- v1.6.3：单张扫描也走 BatchTriage 分诊（识别无异常直接入账）----
    # 少爷问「单张扫的干净票为什么不直接入账」，根因是判据只接了批量一个消费者。
    # 本版把单张也接上 —— 下面四个符号就是这次接线的全部载体。
    "单张入账-辅助函数": b"commitCleanScan",
    "单张入账-提示生成": b"describeForToast",
    "单张入账-toast前缀": "已直接入账：".encode(),
    "单张入账-日志文案": "直接入账（单张·识别无异常）".encode(),
}

# 这些**必须不在**包里 —— 测试类混进正式包会白白增大体积，也说明构建配置有问题
MUST_BE_ABSENT = {
    "DrawTimeTest": b"DrawTimeTest",
    "PrizeRulesTest": b"PrizeRulesTest",
    "RequestBodyTest": b"RequestBodyTest",
    "ConnectivityProbeTest": b"ConnectivityProbeTest",
    "BackupCodecTest": b"BackupCodecTest",
    "BetsTest": b"BetsTest",
    "TicketFilterTest": b"TicketFilterTest",
    "BatchTriageTest": b"BatchTriageTest",
    "ApiKeyMaskTest": b"ApiKeyMaskTest",
    "ImageGuardTest": b"ImageGuardTest",
    "ImageStatsTest": b"ImageStatsTest",
    "ConflictTextTest": b"ConflictTextTest",
    "PrizeSummarizeTest": b"PrizeSummarizeTest",
    "LogRetentionTest": b"LogRetentionTest",
    "TapSequenceTest": b"TapSequenceTest",
    "WipeScopeGuardTest": b"WipeScopeGuardTest",
    "PublicWelfareTest": b"PublicWelfareTest",
    "TicketCrossCheckTest": b"TicketCrossCheckTest",
    "SingleBetMultiplicityTest": b"SingleBetMultiplicityTest",
    "PrizeNoticeTest": b"PrizeNoticeTest",
    "BetFormTest": b"BetFormTest",
    "DltMultiBetTest": b"DltMultiBetTest",
    "TicketHygieneTest": b"TicketHygieneTest",
    "IssueSeqTest": b"IssueSeqTest",
    "JackpotTeaseTest": b"JackpotTeaseTest",
    "TimeAnchorDuplicateTest": b"TimeAnchorDuplicateTest",
    "ScanAutoCommitGuardTest": b"ScanAutoCommitGuardTest",
}

print("APK:", APK, "%.2f MB" % (os.path.getsize(APK) / 1048576))
print("mtime:", __import__("time").strftime(
    "%Y-%m-%d %H:%M:%S", __import__("time").localtime(os.path.getmtime(APK))))

with zipfile.ZipFile(APK) as z:
    blobs = [z.read(n) for n in z.namelist() if n.endswith(".dex")]

print("\n--- needle scan (%d dex) ---" % len(blobs))
missing = []
for name, nd in NEEDLES.items():
    hit = any(nd in b for b in blobs)
    if not hit:
        missing.append(name)
    print("  %-30s %s" % (name, "FOUND" if hit else "MISSING"))

allb = b"".join(blobs)
print("\n--- 旧值应已消失 ---")
for bad in [b"doubao-seed-1-6-vision-250815", b"doubao-seed-2-1-pro-260915",
            "标为单式但存在多注".encode(),
            # v1.4.0 修掉的：Kotlin 文案里残留的 markdown 加粗（界面会显示字面星号）
            "**浮动奖**".encode(),
            "**推理接入点 ID**".encode(),
            # v1.4.0 修正理解后删掉的「真中奖庆祝」文案（那是误解，整块换成了调侃）
            "知道了，去核对金额".encode(),
            "🏆".encode()]:
    print("  %-36s %s" % (bad.decode(), "仍存在(!)" if bad in allb else "已移除"))

# ⚠️ 版本号**不在这里查**。
#
# 曾经这里检查 `b"1.1.0"` / `b"1.2.0"` 在不在 dex 里，想确认旧版本号被替换掉了。
# 结果 1.2.0 一直报「仍存在」—— 查下去发现命中的是 **Kotlin 标准库的文档注释**：
#     "Added since 1.2.0 for binary compatibility with versions <= 1.1.x"
#     "Since 1.2.0, binary compatibility with versions <= 1.1.x"
# 即 kotlin-stdlib 里那些 API 兼容性说明字符串，和本项目的版本号毫无关系。
#
# 结论：**短版本号字符串不能用来搜 dex**，第三方库会一直在那儿命中。
# 版本号只认 APK manifest —— 用 aapt2 读，见下面 badging 校验。
print("\n--- APK 版本号（读 manifest，权威）---")
_aapt = AAPT2
_expected = None
_vp = _VP
if os.path.exists(_vp):
    for _line in open(_vp, encoding="utf-8"):
        _s = _line.strip()
        if _s.startswith("versionName="):
            _expected = _s.split("=", 1)[1].strip()

if os.path.exists(_aapt) and _expected:
    import subprocess as _sp
    _r = _sp.run([_aapt, "dump", "badging", APK],
                 capture_output=True, text=True, encoding="utf-8", errors="replace")
    _m = re.search(r"versionName='([^']+)'", _r.stdout)
    _got = _m.group(1) if _m else None
    if _got == _expected:
        print("  versionName = %s  ✓ 与 version.properties 一致" % _got)
    else:
        print("  !! APK 里是 %r，version.properties 是 %r —— 对不上！" % (_got, _expected))
        missing.append("badging versionName 不一致")
else:
    print("  （跳过：找不到 aapt2 或 version.properties）")

print("\n===== 汇总 =====")
print("应该 FOUND 但缺失：", len(missing))
for m in missing:
    print("   !", m)

leaked = [k for k, v in MUST_BE_ABSENT.items() if any(v in b for b in blobs)]
print("不该在却混进来了：", len(leaked))
for m in leaked:
    print("   !", m)

if not missing and not leaked:
    print("全部通过 ✅")
