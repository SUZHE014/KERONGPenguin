#!/usr/bin/env python3
"""update_knowledge_1694 —— AI-KNOWLEDGE.md 升级到 1.6.9.4 状态。"""
import pathlib
import re

path = pathlib.Path("/home/z/my-project/penguin-git/AI-KNOWLEDGE.md")
text = path.read_text(encoding="utf-8")

# ---------- 1) 头部「最后更新」前置 1.6.9.4 ----------
old_head = "> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-01 五次（**主线 v1.6.9.3 发布**"
new_head = (
    "> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-01 六次（**主线 v1.6.9.4 发布**"
    "（图片卡片背景遮罩样式：render.background-style，默认 gray-mask 原版灰色遮罩不变，"
    "新增 blur-mask 模糊遮罩——参考 EasyBot 液态玻璃模板，降采样 1/8→两趟盒式模糊→双线性放大"
    "等效约 40px 柔化，样式进背景缓存键 reload 切换即时生效，对五卡片+内置图+img 随机图全生效；"
    "修复 QQ 合并转发聊天记录刷屏：QQ 服务端把合并记录渲染成「=== 消息 N ===/[发送者]/[消息内容]」"
    "多行模板串（或 message_type=102）整段下发，旧版原样转发游戏一次几十行，现 QqText."
    "detectMergedForward 双通道判定（102∨双标记）改单行「[聊天记录 共N条]」占位；"
    "reload 可用性逐项核实并写明边界：绝大多数配置即时生效，仅 bot 凭据与 update-check 需重启、"
    "面板用 /kerong panel 手动同步——写入 config 注释四段/reload 反馈/help 行/教程（旧头部"
    "「修改本文件后需重启服务器生效」错误文案一并修正）；异步化审查：AI 对话出 WSS 消息线程、"
    "/kerong reload 双端出主线程（其余链路复核已异步）；config-version 24→25；"
    "门禁 46 + 冒烟 26 全绿；git 远端 main=a68514f 已推送复核）。同日五次：**主线 v1.6.9.3 发布**"
)
assert old_head in text, "头部锚点不匹配"
text = text.replace(old_head, new_head, 1)

# ---------- 2) §0 当前版本前置 1.6.9.4 ----------
old_zero = "当前版本 **1.6.8**（**时长单位小时化 + 排行榜头像修复**"
new_zero = (
    "当前版本 **1.6.9.4**（**背景遮罩样式 + 合并转发防刷屏 + reload 边界 + 异步化**："
    "① render.background-style（gray-mask 默认原版观感不变 / blur-mask 模糊遮罩——"
    "blurImage 降采样 BLUR_DOWNSCALE=8 → boxBlur 两趟 BLUR_BOX_RADIUS=3 可分离整数滑动窗口"
    " → 双线性放大，毫秒级且随背景缓存；样式标签进 processedBackground/processedBuiltinBackground"
    " 缓存键，切换互不污染；内置图 blur 时只模糊不叠遮罩保持原语义；RenderSettings."
    "backgroundStyle() 实时读取，blur-mask/blur/glass→BLUR，乱值→GRAY 兜底）；"
    "② QQ 合并转发（聊天记录）防刷屏——detectMergedForward(metadata,content)："
    "message_type==102（官方 MsgElement 聊天记录类型）∨ 正文含模板标记"
    "（「=== 消息」∧「[发送者]」双标记或「[消息类型] 合并转发消息」，双标记防误伤），"
    "条数取「=== 消息 N ===」最大 N 否则「--- 第N条 ---」最大 N；"
    "forwardFullGroupMessage 识别后改「[聊天记录 共N条]」单行占位（count null 不带数字），"
    "103 引用与正常文本不受影响；③ reload 边界：仅 bot.app-id/bot.secret"
    "（启动连接一次，chs 重连用旧凭据）与 update-check（启动后第 8 秒一次）需重启，"
    "面板按钮用 /kerong panel 手动同步；config.yml 头部总说明/凭据段/update-check 段/"
    "commands 段注释 + 双端 reload 反馈 + help 行 + 教程技术卡四层写明；"
    "④ 异步化：handleAiChat 整体 submitAsync（AiChat.chat 同步 HTTP connect10s+read30s 原阻塞 "
    "WSS 读取线程，期间后续群消息/命令全排队；回复照旧引用原消息，被动回复窗口 5 分钟）、"
    "/kerong reload 双端 submitAsync（磁盘 IO 出主线程）+plugin.submit 回主线程反馈；"
    "门禁 verify_jar_1694 46 项 + 冒烟 smoke1694 26 项全绿） / 1.6.9.3（**变量获取扩展**："
)
assert old_zero in text, "§0 锚点不匹配"
text = text.replace(old_zero, new_zero, 1)

# ---------- 3) §2.1 命令分发行补合并转发与 AI 异步 ----------
old_dispatch = "→ @机器人 AI（**1.6.7.5 三通道判定：GroupAtTracker 帧标记/mentions bot=true/<@文本；AI 未开启落转发**）→ 全量转发。命令用 `@Commands(\"名\")` 注解，多触发名写 `@Commands(\"财富榜\",\"金币榜\")`"
new_dispatch = ("→ @机器人 AI（**1.6.7.5 三通道判定：GroupAtTracker 帧标记/mentions bot=true/<@文本；AI 未开启落转发；1.6.9.4：AI 调用整体 submitAsync——AiChat.chat 同步 HTTP 原阻塞 WSS 读取线程**）"
               "→ 全量转发（**1.6.9.4：合并转发消息（聊天记录）防刷屏——QqText.detectMergedForward 判定 message_type==102 或正文含「=== 消息 N ===+[发送者]」/「[消息类型] 合并转发消息」模板标记，"
               "识别后改单行「[聊天记录 共N条]」占位，不再整段转发几十上百行**）。命令用 `@Commands(\"名\")` 注解，多触发名写 `@Commands(\"财富榜\",\"金币榜\")`")
assert old_dispatch in text, "§2.1 命令分发锚点不匹配"
text = text.replace(old_dispatch, new_dispatch, 1)

# ---------- 4) §2.1 查信息统计卡行（渲染背景样式） ----------
old_render = "头像多级链 + **外部多线路回退（含 MinePic）与 Mojang 网络全局冷却**（1.6.4~1.6.6.1，详见 3.5） |"
new_render = ("头像多级链 + **外部多线路回退（含 MinePic）与 Mojang 网络全局冷却**（1.6.4~1.6.6.1，详见 3.5）；"
              "**1.6.9.4：背景遮罩样式 render.background-style**（gray-mask 原版默认 / blur-mask 模糊遮罩，"
              "全部卡片统一生效，见 3.5） |")
assert old_render in text, "§2.1 渲染行锚点不匹配"
text = text.replace(old_render, new_render, 1)

# ---------- 5) §3.5 渲染管线补样式段 ----------
anchor_35 = "### 3.5 渲染管线与头像获取"
idx = text.find(anchor_35)
assert idx > 0
# 在 3.5 标题后插入一段说明（找到标题行的换行）
insert_at = text.find("\n", idx) + 1
style_block = (
    "> **1.6.9.4 背景（遮罩）样式**：`render.background-style`（gray-mask 默认原版灰色遮罩 / "
    "blur-mask 模糊遮罩——参考 EasyBot 液态玻璃模板观感）。全部卡片统一走 "
    "InfoCardAssets.applyBackgroundStyle（blur 时先 blurImage 再 darkOverlay；内置图本就不叠遮罩、"
    "blur 时仅模糊）；blurImage = 降采样 1/8（BLUR_DOWNSCALE）→ 两趟可分离盒式模糊"
    "（BLUR_BOX_RADIUS=3，整数滑动窗口）→ 双线性放大，等效约 40px 高斯柔化、毫秒级；"
    "样式标签（backgroundStyleTag）进 processedBackground（文件路径#标签）与 "
    "processedBuiltinBackground（builtin:资源#尺寸#标签）缓存键，reload 切样式自动重新处理互不污染。\n"
)
text = text[:insert_at] + style_block + text[insert_at:]

# ---------- 6) §6 坑表：修正过时的 reload 行 + 新增两坑 ----------
old_reload_pit = "| 指令重载配置 | 已移除该概念：改配置一律重启（配置头注释+教程+文档三处一致） |"
new_reload_pit = ("| /kerong reload 边界（1.6.9.4 核实） | 绝大多数配置 reload 即时生效（实时读取）；"
                  "仅 bot.app-id/bot.secret（QQ 客户端启动时连接一次，/kerong chs 也用旧凭据）与 "
                  "update-check（启动后第 8 秒一次）需重启；面板按钮改完用 /kerong panel 手动同步。"
                  "历史行「已移除该概念：改配置一律重启」为过时记录（1.6.9.4 已修正 config 头部误导文案） |")
assert old_reload_pit in text, "§6 reload 行锚点不匹配"
text = text.replace(old_reload_pit, new_reload_pit, 1)

old_last_pit = "| ssh.py 用法 |"
new_pits = (
    "| Kotlin internal 方法字节码带模块后缀 | `internal fun` 编译后方法名是 `cardHeight$common_Bot`（Spigot 产物）"
    "/`cardHeight$server_NeoForge`（NF 产物）——**同一源码在两模块产物后缀不同**，反射调用要按前缀"
    " `startsWith(\"cardHeight\")` 动态查找，写死任一后缀必在另一端 NoSuchMethod（1.6.9.4 冒烟实测） |\n"
    "| javap -v 常量池中文是 Unicode 转义 | javap -v 对常量池里的中文字符串输出 `\\u6d88\\u606f` 形态"
    "（非原文字符）——中文字面量断言必须按**类文件 UTF-8 字节**搜（BMP 区 modified UTF-8 与标准 UTF-8 兼容），"
    "搜 javap 输出文本必假 FAIL（1.6.9.4 门禁 MF3 实测；英文常量两形态收集法仍然适用） |\n"
    "| ssh.py 用法 |"
)
assert old_last_pit in text, "§6 末行锚点不匹配"
text = text.replace(old_last_pit, new_pits, 1)

# ---------- 7) 新 §7 块（1.6.9.4）置顶 ----------
old_s7 = "## 7. 当前已知状态（2026-11-01 五次会话，v1.6.9.3 完成）"
new_s7 = """## 7. 当前已知状态（2026-11-01 六次会话，v1.6.9.4 完成）

- **v1.6.9.4 已发布上线**（双 JAR / version.json / index.html / tutorial.html 全部在线验证通过，git 远端 main=a68514f 已推送直连复核）。四项需求全落地：
- **背景遮罩样式**：RenderSettings 新增 BackgroundStyle 枚举 + backgroundStyle()（blur-mask/blur/glass→BLUR_MASK；乱值/留空→GRAY_MASK 兜底）；InfoCardAssets 新增 applyBackgroundStyle/blurImage/boxBlur/boxBlurAxis/backgroundStyleTag 五方法；样式进双缓存键（随机图 `路径#gray|blur`、内置图 `builtin:资源#WxH#gray|blur`）；内置图 blur 时只模糊不叠暗色遮罩（保持「设计自带可读性不叠遮罩」的原语义）；配置版本 24→25（DEFAULT_VALUES 补 render.background-style=gray-mask）。视觉复核：VisualCompare1694（彩色渐变+色块+高频细纹测试图）数值验证 blur 局部方差降 96.7%、边缘强度降 61.6%、亮度一致（暗色遮罩等效）。
- **合并转发防刷屏**：QQ 官方文档 MsgElement.message_type=102=聊天记录；实测（zaofan-make/dsh-qqbot#8）QQ 服务端把合并记录渲染成「=== 消息 N ===/[消息内容]/[发送者]/[消息类型] 合并转发消息/[关联消息]/--- 第N条 ---」多行模板串整段下发（嵌套层用 --- 第N条 ---）；detectMergedForward 双通道判定 + 双正则取最大条数；占位「[聊天记录 共N条]」与 [图片]/[语音]/[视频] 同风格。
- **reload 边界**：ConfigManager.reload() 重读配置+fillMissing+版本迁移、reloadRuntimeConfig() 重建 Markdown 模板+MessageTemplates+自定义命令、全部 getter 实时读 plugin.config（groupOpenIds/commandSwitches/chatFormat/playerEventFormat/RenderSettings.*/variable-fetch 逐查询读取）；唯一不能 reload 的两类：QQ 凭据（launchQqClient 仅启动一次，chs=resumeConnection 用旧会话）与 update-check（scheduleUpdateCheck 启动后 8 秒一次）；面板不随 reload（/kerong panel 手动同步）。写入位置：config.yml 头部总说明+bot 凭据段+update-check 段+commands 段（双端源+JAR 内置）、双端 /kerong reload 完成反馈、双端 help 行、官网教程技术卡「哪些配置不能用 /kerong reload」一节。
- **异步化审查**：已异步链路清单（五卡片 CardRenderPool、游戏→QQ OutboxQueue、进出服播报 OutboxQueue、游戏内 AI FoliaCompat.runAsync、PlayerStatsManager 保存 submitAsync、头像清扫后台线程、UpdateChecker 独立线程、面板同步 1.6.7.10 起、命令执行 CompletableFuture、敏感词审核 1.5.4.5 起）；本轮修复：handleAiChat 整体 submitAsync（原 AiChat.chat 在 WSS 读取线程同步跑 HTTP，AI 慢时后续群消息/命令全排队）+ /kerong reload 双端 submitAsync（原在主线程做配置读写/损坏恢复/模板释放/补齐保存）。
- 冒烟基建：smoke1694/FakeKeronRuntime（继承 NF 兼容层 JavaPlugin + 实现 cn.kerong.bot.KERONG 接口——Java 实现 Kotlin interface 的 val 生成 getX()，五 provider 19 成员全 stub；注册 BotShared.setInstance 后 RenderSettings 的「BotShared.instance as? JavaPlugin」配置通路在冒烟 JVM 端到端打通）。
- 本轮踩坑三枚已入 §6：NF 打包 base 误传 NF JAR（shaded 类被当旧字节码剔除 19MB→6MB，已有旧坑条目重演——**看产物体积骤降要立刻起疑**）；internal 方法模块后缀；javap 中文 Unicode 转义。
- 环境注：本会话 tooling 位于 /tmp/my-project/tooling（JDK21/Gradle home/deps），经符号链接 /home/z/my-project/tooling 挂回（gradle.properties 的 java.installations.paths 指向 /home/z 路径）；首次 gradle 启动约 80 秒；git push 直连 github.com 成功（本轮未走服务器中继）。

"""
assert old_s7 in text, "§7 锚点不匹配"
text = text.replace(old_s7, new_s7 + old_s7.replace("## 7. 当前已知状态（2026-11-01 五次会话，v1.6.9.3 完成）", "## 7.（上一轮状态，v1.6.9.3 —— 历史归档）"), 1)

path.write_text(text, encoding="utf-8")
print("AI-KNOWLEDGE.md 更新完成：头部 / §0 / §2.1×2 / §3.5 / §6（1 改 2 增）/ 新 §7 块")
