#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AI-KNOWLEDGE.md 同步到 1.6.7.5（§0 头部/版本段、§2 两行、§7 状态块）。"""
import re

PATH = "AI-KNOWLEDGE.md"
with open(PATH, encoding="utf-8") as f:
    text = f.read()

# ---- 1. 头部最后更新行 ----
text = text.replace(
    "> 面向：接手开发/发版的 AI 助手。最后更新：2026-10-18（v1.6.7.4 发版完成：spark 提示时机/门控重做、日志 512KB、控制台降噪）。",
    "> 面向：接手开发/发版的 AI 助手。最后更新：2026-10-18（v1.6.7.5 发版完成：Spark 反射链修复（装了也读不到）、AI 聊天艾特判定（群消息从未生效）、查性能卡实色化）。",
)

# ---- 2. §0 版本段：当前版本改 1.6.7.5，旧段降为历史 ----
text = text.replace(
    "**版本规则**：插件版与模组版同步发版、同一版本号（如 1.6.7.4）。当前版本 **1.6.7.4**（",
    "**版本规则**：插件版与模组版同步发版、同一版本号（如 1.6.7.5）。当前版本 **1.6.7.5**（**SparkBridge 反射链修复**：spark 的 SparkImpl 与各统计实现类全部包私有，旧版对 obj.javaClass 取 Method 反射调用抛 IllegalAccessException 被逐项容错吞掉 → TPS/MSPT/CPU 全 null（启动自检报「检测到 spark 但接入失败（各指标均暂不可读）」、/查性能 数据从未走过 spark——1.6.7.2~1.6.7.4 均受影响，手写假 spark 冒烟恰好是 public 类所以没暴露）；现方法一律从公共 API 接口解析（Spark/DoubleStatistic/GenericStatistic/DoubleAverageInfo 的 Class.forName + getMethod + isAssignableFrom 擦除匹配 poll(Enum)），已对真实 spark 1.10.187 字节码逐项核实（含 Statistic#name()/getWindows()、SparkProvider.get() 未加载抛 IllegalStateException）并以「真实形态假 spark + 包私有实现 + 独立 URLClassLoader」端到端冒烟；启动自检失败先 15 秒静默复测再定性；**AI 聊天艾特判定修复**：QQ 群消息 content 已被平台去除 @机器人前缀、mentions 不含机器人自身（官方文档明确），旧「content 含 <@」门槛对群消息永远不成立 → @机器人 从未被判定为 AI 对话全部落转发；现 WssDiagnostics 帧层捕获 GROUP_AT_MESSAGE_CREATE 的 d.id 记入 GroupAtTracker（帧钩子先于 SDK 分发执行，重连安全），群消息入口三通道判定（帧标记/mentions bot=true/<@文本）；附带 RecentMessageGate msg_id 级去重（全量模式同一条 @ 消息双事件先后到达 + QQ 弱网重投递——同一条消息只处理一次）与 QqText.stripLeadingMentionPlaceholders 行首占位剥离（全量模式 content 原样保留 <@openid> 前缀，@机器人 /命令 此匹配不到命令）；AI 未开启时 @ 消息不再被吞落全量转发；**查性能卡实色化**：指标卡/容器/胶囊/进度轨全部实色不透明深板+细描边（无任何 alpha 混合），半透明玻璃面板（用户反馈「液态玻璃效果」）彻底移除；）/ 1.6.7.4（",
)

# ---- 3. §2 查性能行与命令分发行 ----
text = text.replace(
    "性能监控卡片（固定四项 TPS/MSPT/内存/CPU，状态分级+进度条，null 显示「--」，固定原版毛玻璃深色）；",
    "性能监控卡片（固定四项 TPS/MSPT/内存/CPU，状态分级+进度条，null 显示「--」，**1.6.7.5 实色不透明深板+细描边**）；",
)
text = text.replace(
    "**1.6.7.2：装了 spark 时优先 SparkBridge 纯反射调其公开 API**——SparkProvider.get() 单例→tps().poll(TicksPerSecond.MINUTES_1)/mspt().poll(MillisPerTick.SECONDS_10).mean()/cpuProcess().poll(CpuUsage.MINUTES_1)，",
    "**1.6.7.2 引入 / 1.6.7.5 修复反射链：装了 spark 时优先 SparkBridge 反射调其公开 API，方法一律从公共接口解析（实现类包私有，从实现类取 Method invoke 抛 IllegalAccessException 全指标被吞——1.6.7.5 前的暗坑）**——SparkProvider.get() 单例→tps().poll(TicksPerSecond.MINUTES_1)/mspt().poll(MillisPerTick.SECONDS_10).mean()/cpuProcess().poll(CpuUsage.MINUTES_1)，",
)
text = text.replace(
    "群消息入口：ReplySeqGuard 基数分配 → 纯数字自动绑定 → 命令分发（首个匹配短路）→ @机器人 AI → 全量转发。",
    "群消息入口：ReplySeqGuard 基数分配 → **1.6.7.5：RecentMessageGate msg_id 去重（双事件/重投递）+ QqText.stripLeadingMentionPlaceholders 行首占位剥离** → 纯数字自动绑定 → 命令分发（首个匹配短路）→ @机器人 AI（**1.6.7.5 三通道判定：GroupAtTracker 帧标记/mentions bot=true/<@文本；AI 未开启落转发**）→ 全量转发。",
)

# ---- 4. §7 新状态块置顶 ----
old7 = "## 7. 当前已知状态（2026-10-18，v1.6.7.4 完成）"
new7 = """## 7. 当前已知状态（2026-10-18，v1.6.7.5 完成）

- 双端 1.6.7.5 全部完成（门禁 verify_jar_1675（在 1674 的 287 项全保持基础上新增 SB 组反射链修复 5 项 / GA 组艾特判定 10 项 / PRS 组实色面板 3 项 / S40~S46 源码断言 / V 组 1.6.7.5）/ 冒烟 **smoke1675 34 项**（1.x 真实形态假 spark：包私有实现+独立 URLClassLoader 端到端（修复前必挂的用例）+ 未装配 null + 全新 loader null；2.x 三态与 schedule 四路径；3.x GroupAtTracker/RecentMessageGate/QqText 剥离 12 项；4.x 性能卡渲染逐像素=实色常量（0x101319/0x151A21/0x1E242D，证明无 alpha 混合）；5.x 行内格式与 512KB 轮转回归；6.x JAR 版本 1.6.7.5）/ 官网部署 / git 推送）：
  - 修复①：SparkBridge 反射链（见 §0；**坑：反射第三方库时绝不对 obj.javaClass 取方法——实现类可能包私有，一律从其公共接口 Class.forName 解析**；冒烟假对象必须模拟真实可见性（包私有实现类），public 假类会漏掉 IllegalAccessException 路径）；
  - 修复②：AI 聊天艾特判定（见 §0；**群消息 @机器人 的唯一可靠信号是事件类型 GROUP_AT_MESSAGE_CREATE 本身**——content 前缀被平台剥、mentions 不含机器人；WssDiagnostics 帧钩子在 SDK 分发前执行，同帧先记后用天然有序，重连/重投递安全；全量模式（GROUP_MESSAGE_CREATE）content 反而保留 <@openid> 前缀且 mentions 含 bot=true 条目——两种事件形态相反，三通道覆盖）；
  - 调整③：查性能卡实色面板（COLOR_CARD=0x151A21/COLOR_CONTAINER=0x101319/COLOR_PILL_BG=0x0D1116/COLOR_BAR_TRACK=0x1E242D+BasicStroke 1.5f 描边；GlobalOpacity 逻辑不动）；
  - 版本：1.6.7.5 / config-version 22（无配置键增删，版本不升）。
以下为 1.6.7.4 历史项（门禁 287 项：SC 组 ServerLoadEvent/三态/门控、LB 组 512KB、CN 组降噪三条、S31~S39、V 组；冒烟 smoke1674 20 项）："""
text = text.replace(old7, new7 + "\n\n- 双端 1.6.7.4 全部完成")

with open(PATH, "w", encoding="utf-8") as f:
    f.write(text)
print("AI-KNOWLEDGE.md 已同步 1.6.7.5")
