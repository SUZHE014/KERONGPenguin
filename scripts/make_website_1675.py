#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 1.6.7.4 发布目录生成 1.6.7.5 官网三文件（index/version.json/tutorial）。"""
import json
import os
import re

SRC_DIR = "/home/z/my-project/download/kerongpenguin-1.6.7.4-release"
DST_DIR = "/home/z/my-project/download/kerongpenguin-1.6.7.5-release"
os.makedirs(DST_DIR, exist_ok=True)

# ---------------- index.html ----------------
with open(os.path.join(SRC_DIR, "index.html"), encoding="utf-8") as f:
    html = f.read()

# 主下载框
html = html.replace(
    '<p style="font-size:1.3em;font-weight:bold;color:#333;margin-bottom:5px">版本 1.6.7.4</p>',
    '<p style="font-size:1.3em;font-weight:bold;color:#333;margin-bottom:5px">版本 1.6.7.5</p>',
)
html = html.replace(
    '<a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.7.4.jar" class="dl-btn" style="display:block;max-width:440px;margin:0 auto 12px;box-sizing:border-box">⬇ 下载 v1.6.7.4（Spigot 插件版）</a>',
    '<a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.7.5.jar" class="dl-btn" style="display:block;max-width:440px;margin:0 auto 12px;box-sizing:border-box">⬇ 下载 v1.6.7.5（Spigot 插件版）</a>',
)
html = html.replace(
    '<a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.21.1-1.6.7.4.jar" class="dl-btn" style="display:block;max-width:440px;margin:0 auto 12px;box-sizing:border-box">⬇ 下载 v1.6.7.4（NeoForge 模组版）</a>',
    '<a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.21.1-1.6.7.5.jar" class="dl-btn" style="display:block;max-width:440px;margin:0 auto 12px;box-sizing:border-box">⬇ 下载 v1.6.7.5（NeoForge 模组版）</a>',
)

# 历史表：1.6.7.4 置顶两行；顺手把 1.6.7.3 两行的链接修指向真实 1.6.7.3 JAR；
# 删最老的 1.6.6.3 两行保持表长
nf74 = '<tr><td>NeoForge 1.6.7.4</td><td>2026-10-18</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.21.1-1.6.7.4.jar">下载</a></td></tr>'
sp74 = '<tr><td>Spigot 1.6.7.4</td><td>2026-10-18</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.7.4.jar">下载</a></td></tr>'
html = html.replace(
    '<tr><td>NeoForge 1.6.7.3</td><td>2026-10-18</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.21.1-1.6.7.4.jar">下载</a></td></tr>',
    nf74 + "\n" + '<tr><td>NeoForge 1.6.7.3</td><td>2026-10-18</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.21.1-1.6.7.3.jar">下载</a></td></tr>',
)
html = html.replace(
    '<tr><td>Spigot 1.6.7.3</td><td>2026-10-18</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.7.4.jar">下载</a></td></tr>',
    sp74 + "\n" + '<tr><td>Spigot 1.6.7.3</td><td>2026-10-18</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.7.3.jar">下载</a></td></tr>',
)
html = html.replace(
    '<tr><td>NeoForge 1.6.6.3</td><td>2026-10-16</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.21.1-1.6.6.3.jar">下载</a></td></tr>\n', "",
)
html = html.replace(
    '<tr><td>Spigot 1.6.6.3</td><td>2026-10-16</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.6.3.jar">下载</a></td></tr>\n', "",
)

# 更新日志：v1.6.7.5 置顶
v1675 = """<div class="version"><h3>v1.6.7.5</h3><span class="date">2026-10-18</span><ul>
<li><b>修复：安装了 Spark 也检测不到、指标全部读不到</b>——1.6.7.2 起 /查性能 联动 Spark 的反射调用此前从实现类解析方法，而 Spark 的实现类全部是包私有（package-private），反射 invoke 被拒绝后逐项容错吞掉，TPS / MSPT / CPU 全部为 null：启动自检一直报「检测到 spark 但接入失败（各指标均暂不可读）」、查询数据也从未走过 Spark。现改为一律从 Spark 公共 API 接口解析（与实现类可见性无关），已对真实 Spark 1.10.187 逐项核实签名并端到端冒烟；启动自检接入失败时还会先静默复测一轮（15 秒）再定性，避免启动初期统计窗口未热误报。装了 Spark 的服务器重启后即可看到「已接入 spark」正向确认。</li>
<li><b>修复：群里 @机器人 发消息从未被判定为 AI 对话</b>——QQ 官方群消息协议会把消息正文里的 @机器人 前缀自动去除、mentions 列表也不含机器人自身，此前「正文含 @ 片段」的判定对群消息永远不成立（频道消息才有效），@机器人 的消息全部被当作普通聊天转发。现改在 WebSocket 帧层捕获「群 @机器人 消息」事件判定（三通道：事件标记 / mentions 机器人条目 / @ 文本）。附带三项加固：同一条消息只处理一次（全量模式下 @ 消息可能以两种事件到达、QQ 网关弱网会重投递——杜绝重复回复 / 重复转发）；<code>@机器人 /命令</code> 现在能正常匹配命令（此前命令正文前的 @ 占位符导致匹配失败）；AI 功能未开启时 @ 消息不再凭空消失（正常落到群消息转发）。</li>
<li><b>调整：/查性能 卡片改实色不透明面板</b>——指标卡 / 状态胶囊 / 进度轨道全部改为实色深板 + 细描边，不再有任何半透明玻璃质感（此前半透明面板会被看成「液态玻璃」效果），深色背景上的数字对比度更高、监控数据更易读；字体、布局与指标口径均不变。</li>
</ul></div>
"""
html = html.replace(
    '<div class="version"><h3>v1.6.7.4</h3><span class="date">2026-10-18</span><ul>',
    v1675 + '<div class="version"><h3>v1.6.7.4</h3><span class="date">2026-10-18</span><ul>',
    1,
)

# 设计来源段：查性能卡片现为实色深板
html = html.replace(
    "图片渲染界面（/查信息卡片 / /查在线列表 / 排行榜 / 查性能卡片）的毛玻璃深色风格",
    "图片渲染界面（/查信息卡片 / /查在线列表 / 排行榜的毛玻璃深色风格与查性能卡片的实色深板）",
)

with open(os.path.join(DST_DIR, "index.html"), "w", encoding="utf-8") as f:
    f.write(html)
print("index.html 完成")

# ---------------- version.json ----------------
with open(os.path.join(SRC_DIR, "version.json"), encoding="utf-8") as f:
    meta = json.load(f)

entry_1675 = (
    "1.6.7.5（3 项修复）：修复安装了 Spark 也检测不到——反射方法此前从实现类解析而被逐项拒绝（Spark 实现类全部包私有），"
    "TPS/MSPT/CPU 全 null、启动自检报「接入失败（各指标均暂不可读）」，现一律从 Spark 公共 API 接口解析并增加启动期 15 秒静默复测；"
    "修复群里 @机器人 从未被判定为 AI 对话——QQ 群消息正文会被平台自动去除 @机器人 前缀且 mentions 不含机器人自身，旧判定永远不成立，"
    "现改帧层捕获「群 @机器人 消息」事件判定（附同一条消息去重与 @机器人+命令 的正常匹配）；"
    "/查性能 卡片改实色不透明深板面板，彻底去除玻璃质感"
)
meta["version"] = "1.6.7.5"
meta["changelog"] = [entry_1675] + meta["changelog"][:-1]  # 新条目置顶，末位淘汰保持 8 条

with open(os.path.join(DST_DIR, "version.json"), "w", encoding="utf-8") as f:
    json.dump(meta, f, ensure_ascii=False, indent=2)
print("version.json 完成")

# ---------------- tutorial.html ----------------
with open(os.path.join(SRC_DIR, "tutorial.html"), encoding="utf-8") as f:
    tut = f.read()

tut = tut.replace(
    "<b>装了 Spark（插件 / 模组）时优先直接调用 Spark 取数</b>（1.6.7.2，无需任何配置）",
    "<b>装了 Spark（插件 / 模组）时优先直接调用 Spark 取数</b>（1.6.7.2 起；1.6.7.5 修复了此前装了也读不到的反射缺陷，无需任何配置）",
)
tut = tut.replace(
    "<p>群里 @机器人 即可对话（服务器内也可开启，玩家输入前缀触发）。支持 DeepSeek / OpenAI 等兼容接口：</p>",
    "<p>群里 @机器人 即可对话（1.6.7.5 修复：QQ 群消息的 @ 前缀会被平台剥离，此前群里 @机器人 从未被识别为 AI 对话；现在 @机器人 + 直接说话触发 AI，@机器人 + /命令 也正常执行命令）；服务器内也可开启，玩家输入前缀触发。支持 DeepSeek / OpenAI 等兼容接口：</p>",
)

with open(os.path.join(DST_DIR, "tutorial.html"), "w", encoding="utf-8") as f:
    f.write(tut)
print("tutorial.html 完成")
