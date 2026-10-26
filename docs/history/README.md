# 历史修改记录

现有记录按版本保留。报告中的命令、路径和功能状态属于当时版本；当前构建步骤以 `docs/BUILD.md` 为准。这里只对本机路径和设备标识做脱敏，不修改测试成败。

## 逐版报告

- [VALIDATION.txt](versions/VALIDATION.txt)
- [VALIDATION-0.2.txt](versions/VALIDATION-0.2.txt)
- [VALIDATION-0.3.txt](versions/VALIDATION-0.3.txt)
- [VALIDATION-0.3.1.txt](versions/VALIDATION-0.3.1.txt)
- [VALIDATION-0.4.txt](versions/VALIDATION-0.4.txt)
- [VALIDATION-0.5.txt](versions/VALIDATION-0.5.txt)
- [VALIDATION-0.6.txt](versions/VALIDATION-0.6.txt)
- [VALIDATION-0.7.txt](versions/VALIDATION-0.7.txt)
- [VALIDATION-0.8.txt](versions/VALIDATION-0.8.txt)
- [VALIDATION-0.9.txt](versions/VALIDATION-0.9.txt)
- [VALIDATION-0.10.txt](versions/VALIDATION-0.10.txt)
- [VALIDATION-0.11.txt](versions/VALIDATION-0.11.txt)
- [VALIDATION-0.12.txt](versions/VALIDATION-0.12.txt)
- [VALIDATION-0.13.txt](versions/VALIDATION-0.13.txt)
- [VALIDATION-0.14.txt](versions/VALIDATION-0.14.txt)
- [VALIDATION-0.15.txt](versions/VALIDATION-0.15.txt)
- [VALIDATION-0.16.txt](versions/VALIDATION-0.16.txt)
- [VALIDATION-0.16.2.txt](versions/VALIDATION-0.16.2.txt)
- [VALIDATION-0.17.txt](versions/VALIDATION-0.17.txt)
- [VALIDATION-0.18.txt](versions/VALIDATION-0.18.txt)
- [VALIDATION-0.19.txt](versions/VALIDATION-0.19.txt)
- [VALIDATION-0.20.txt](versions/VALIDATION-0.20.txt)
- [VALIDATION-0.21.txt](versions/VALIDATION-0.21.txt)
- [VALIDATION-0.22.txt](versions/VALIDATION-0.22.txt)
- [VALIDATION-0.22.1.txt](versions/VALIDATION-0.22.1.txt)
- [VALIDATION-0.23.txt](versions/VALIDATION-0.23.txt)
- [VALIDATION-0.24.txt](versions/VALIDATION-0.24.txt)
- [VALIDATION-0.25.txt](versions/VALIDATION-0.25.txt)
- [VALIDATION-0.26.txt](versions/VALIDATION-0.26.txt)
- [VALIDATION-0.27.txt](versions/VALIDATION-0.27.txt)
- [VALIDATION-0.28.txt](versions/VALIDATION-0.28.txt)
- [VALIDATION-0.29.txt](versions/VALIDATION-0.29.txt)
- [VALIDATION-0.30.txt](versions/VALIDATION-0.30.txt)
- [VALIDATION-0.31.txt](versions/VALIDATION-0.31.txt)
- [VALIDATION-0.32.0.txt](versions/VALIDATION-0.32.0.txt)
- [VALIDATION-0.33.0.txt](versions/VALIDATION-0.33.0.txt)
- [VALIDATION-0.34.0.txt](versions/VALIDATION-0.34.0.txt)
- [VALIDATION-0.34.1.txt](versions/VALIDATION-0.34.1.txt)
- [VALIDATION-0.35.0.txt](versions/VALIDATION-0.35.0.txt)
- [VALIDATION-0.36.0.txt](versions/VALIDATION-0.36.0.txt)
- [VALIDATION-0.36.1.txt](versions/VALIDATION-0.36.1.txt)
- [VALIDATION-0.36.2.txt](versions/VALIDATION-0.36.2.txt)
- [VALIDATION-0.37.0.txt](versions/VALIDATION-0.37.0.txt)
- [VALIDATION-0.37.1.txt](versions/VALIDATION-0.37.1.txt)
- [VALIDATION-0.38.0.txt](versions/VALIDATION-0.38.0.txt)
- [VALIDATION-0.58.0.txt](versions/VALIDATION-0.58.0.txt)
- [VALIDATION-0.59.txt](versions/VALIDATION-0.59.txt)
- [VALIDATION-0.60.txt](versions/VALIDATION-0.60.txt)
- [VALIDATION-0.60.2.txt](versions/VALIDATION-0.60.2.txt)
- [UPDATES-0.60.2-0.60.7.txt](versions/UPDATES-0.60.2-0.60.7.txt)
- [VALIDATION-0.60.3.txt](versions/VALIDATION-0.60.3.txt)
- [VALIDATION-0.60.4.txt](versions/VALIDATION-0.60.4.txt)
- [VALIDATION-0.60.5.txt](versions/VALIDATION-0.60.5.txt)
- [VALIDATION-0.60.7.txt](versions/VALIDATION-0.60.7.txt)
- [VALIDATION-0.61.1.txt](versions/VALIDATION-0.61.1.txt)
- [VALIDATION-0.61.2.txt](versions/VALIDATION-0.61.2.txt)

## 后续补充

- [0.61.3 公用 AI / 短语音与快速入门验证](evidence/0.61.3/completion.json)
- [0.61.4 配置页、执行状态与小助手预览验证](evidence/0.61.4/review.json)
- [0.61.4 本次源码差异](patches/)

## 自主测试迭代

`evidence/ai-audit/` 保留中间失败、完整轮次、针对性复测和最终报告，不把多次重跑相加为独立场景，也不把模拟测试等同于真机麦克风或通知到达验收。历史日志中引用的 APK、临时路径或备份未随包提供。

## 当前打包检验

此前源码快照的构建结果见 [构建与测试记录](../../校验/此前0.61.4/构建与测试记录.json)；本次目录整理见 [整理检查](../../校验/此前0.61.4/本次整理检查.json)，均不替代过去版本的原始记录。

## 0.64.0 补充

- [版本变化](../RELEASE-0.64.0.md)
- [原始验证汇总](evidence/0.64.0/result-summary.json)
- [0.61.4 到 0.64.0 的应用与后端差异](patches/0.61.4-to-0.64.0.patch)

原始报告保留当时状态，本次结果见当前版本说明及校验目录。未收录私人聊天及原始用户数据。

## 1.0.0 发布版

- [发布说明](../RELEASE-1.0.0.md)
- [0.64.2 口语验证汇总](evidence/0.64.2/summary.json)
- [0.64.0 到 1.0.0 代码差异](patches/0.64.0-to-1.0.0.patch)
- [当前验证记录](../../校验/1.0.0/README.md)
