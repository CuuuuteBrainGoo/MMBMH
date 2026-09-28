# 本次任务概览

**日期**：2026-09-28
**任务**：执行三项待办（删依赖 / 守卫自动化 / git 历史瘦身）+ 排查并解决「Releases 页面与 README 徽章不更新」

---

## 做了什么

1. **删掉 `androidx.navigation:navigation-compose` 冗余依赖**
   确认全工程零引用后移除，包体积下降约 1 MB（debug / release 双向）。

2. **把 `_release_guard.py` 焊进 `_pack.py`**
   打包前自动校验 4 处版本号一致性，不一致直接中断打包。首次并入就真拦住一次。

3. **git 历史瘦身**
   `.git` 从 65 MB 降到 882 KB。过程中 `filter-branch` 误删了要保留的文档，
   已从整仓备份恢复并重建，数据零丢失。

4. **升级 `推送到GitHub.bat`**
   改为两段式推送：普通 push 失败时，提示用户确认后自动走 `--force-with-lease`。
   已通过字节级校验 + cmd 模拟实跑验证。

5. **推送上线 + 建 Release**
   强制推送成功；tag 全部重指；建 v1.6.1 Release（含两个 APK）。

---

## 关键结论

- **根因**：README 徽章读的是 GitHub **Release**，不是仓库文件。
  「代码 push 成功」不等于「Release 存在」。发版必须 commit → push → 建 Release 三步齐全。
- **实测验证**：徽章现在渲染为 `release: v1.6.1`，少爷反映的问题已解决。
- **沙箱能推 GitHub**：只要少爷先跑过一次 bat（代理写进 repo 的 git config）。
  旧记忆里「沙箱推不上去是既定设计」这条结论是错的，已更正。

---

## 产出文件

| 文件 | 说明 |
|---|---|
| `.workbuddy/artifacts/2026-09-28-收尾报告.md` | 本次收尾完整报告 |
| `.workbuddy/memory/2026-09-28.md` | 当日工作流水（含事故复盘） |
| `.workbuddy/memory/MEMORY.md` | 长期约定更新（3 条新规则 + 1 条更正） |
| `推送到GitHub.bat` | 升级为两段式推送（本机文件，不进仓库） |

---

## 遗留（不影响使用）

- 远端仓库 `size` 仍报 64 MB —— 服务端 gc 未执行，客户端已干净，无需处理
- 旧版 Release 附件保留 —— 属合理行为，用户可下载历史版本
