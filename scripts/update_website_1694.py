#!/usr/bin/env python3
"""update_website_1694 —— 官网三文件升级到 1.6.9.4（以线上当前版为底稿）。

用法：python3 scripts/update_website_1694.py
输入：/tmp/web_index.html /tmp/web_version.json /tmp/web_tutorial.html（线上拉取的底稿）
输出：/tmp/web_out/{index.html,version.json,tutorial.html}
"""
import json
import pathlib

OUT = pathlib.Path("/tmp/web_out")
OUT.mkdir(exist_ok=True)

NEW_VERSION = "1.6.9.4"
NF_JAR = f"KERONGPenguin_NeoForge-1.20.4-1.6.9.4.jar"
SP_JAR = f"KERONGPenguin_Spigot-1.6.9.4.jar"
BASE = "https://kerong.xin/penguin/downloads/"

# ---------------- index.html ----------------

index = pathlib.Path("/tmp/web_index.html").read_text(encoding="utf-8")

# 1) 主下载框
old_box = """<p style="font-size:1.3em;font-weight:bold;color:#333;margin-bottom:5px">版本 1.6.9.3</p>
<p style="color:#999;margin-bottom:20px">发布于 2026-11-01 · 变量获取扩展（玩家名 / TPS / 自定义项目）+ 查性能最大人数 + 命令精简</p>
<a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.9.3.jar" class="dl-btn" style="display:block;max-width:440px;margin:0 auto 12px;box-sizing:border-box">⬇ Spigot 插件版</a>
<a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.6.9.3.jar" class="dl-btn" style="display:block;max-width:440px;margin:0 auto 12px;box-sizing:border-box">⬇ NeoForge 模组版</a>"""
new_box = """<p style="font-size:1.3em;font-weight:bold;color:#333;margin-bottom:5px">版本 1.6.9.4</p>
<p style="color:#999;margin-bottom:20px">发布于 2026-11-01 · 背景遮罩样式（原版灰色 / 新增模糊毛玻璃）+ 合并转发防刷屏 + reload 边界明确 + 异步化</p>
<a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.9.4.jar" class="dl-btn" style="display:block;max-width:440px;margin:0 auto 12px;box-sizing:border-box">⬇ Spigot 插件版</a>
<a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.6.9.4.jar" class="dl-btn" style="display:block;max-width:440px;margin:0 auto 12px;box-sizing:border-box">⬇ NeoForge 模组版</a>"""
assert old_box in index, "主下载框底稿不匹配"
index = index.replace(old_box, new_box)

# 2) 历史版本表：1.6.9.4 两行置顶，删 1.6.7.9 两行（保持 6 版）
table_head = """<tr><th>版本</th><th>发布日期</th><th>下载</th></tr>
<tr><td>NeoForge 1.6.9.3</td><td>2026-11-01</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.6.9.3.jar">下载</a></td></tr>"""
assert table_head in index, "历史表头不匹配"
new_table_head = """<tr><th>版本</th><th>发布日期</th><th>下载</th></tr>
<tr><td>NeoForge 1.6.9.4</td><td>2026-11-01</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.6.9.4.jar">下载</a></td></tr>
<tr><td>Spigot 1.6.9.4</td><td>2026-11-01</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.9.4.jar">下载</a></td></tr>
<tr><td>NeoForge 1.6.9.3</td><td>2026-11-01</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.6.9.3.jar">下载</a></td></tr>"""
index = index.replace(table_head, new_table_head)

old_179 = """<tr><td>NeoForge 1.6.7.9</td><td>2026-10-29</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.6.7.9.jar">下载</a></td></tr>
<tr><td>Spigot 1.6.7.9</td><td>2026-10-29</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.6.7.9.jar">下载</a></td></tr>
"""
assert old_179 in index, "1.6.7.9 两行不匹配"
index = index.replace(old_179, "")

# 3) 更新日志：v1.6.9.4 段置顶，删 v1.6.7.9 段（随版本下架同步删）
changelog_new = """<div class="changelog">
<div class="version"><h3>v1.6.9.4</h3><span class="date">2026-11-01</span><ul>
<li><b>新增：图片卡片背景遮罩样式</b>（render.background-style）——默认 <code>gray-mask</code> 原版灰色遮罩（背景保留全部细节，观感与旧版一致）；改成 <code>blur-mask</code> 切换<b>模糊遮罩样式</b>（参考 EasyBot 液态玻璃图片模板）：背景先做高斯模糊再叠暗色遮罩，柔化成毛玻璃色块，前景卡片与文字对比更突出。对查信息 / 查在线 / 查性能 / 在线排行榜 / 财富榜全部生效（含内置背景图与 img/ 自定义图）；模糊在每张背景图处理时做一次后缓存，渲染速度与原版一致；<code>/kerong reload</code> 切换样式即时生效。</li>
<li><b>修复：QQ 群合并转发聊天记录刷屏游戏聊天</b>——群友转发合并聊天记录时，QQ 会把整段记录渲染成「=== 消息 N === / [消息内容] / [发送者]」样式的多行文本下发，旧版整段原样转发进游戏，一次刷几十上百行；现自动识别（含 message_type=102 聊天记录事件），改为单行占位 <b>「[聊天记录 共N条]」</b>（与 [图片] / [语音] 占位同风格，条数解析不到时不带数字），想看内容回 QQ 群点开卡片即可；普通文字与引用消息不受影响。</li>
<li><b>明确：/kerong reload 的可用范围</b>——绝大多数配置（命令开关 / 群列表 / 聊天转发 / 播报格式 / 消息模板 / 渲染设置 / 变量获取 / AI 参数等）reload 立即生效；<b>仅两项必须重启服务器</b>：①bot.app-id / bot.secret（QQ 凭据，只在启动时连接一次，/kerong chs 重连也用旧凭据）；②update-check 段（更新检查只在启动后第 8 秒执行一次）。QQ 快捷面板按钮不随 reload 自动更新，用 <code>/kerong panel</code> 手动同步即可（无需重启）。以上边界已写入 config.yml 注释（头部总说明 / 凭据段 / update-check 段 / commands 段）、/kerong reload 执行反馈与官网教程。</li>
<li><b>优化：能用异步就用异步</b>——本轮审查后修复两处：①AI 对话调用从 QQ 消息线程移入后台线程（AI 接口响应慢时不再卡住后续群消息与命令响应）；②/kerong reload 改为后台执行（配置文件读写不再占用服务器主线程，完成后反馈结果）。其余链路（图片渲染 / 聊天转发 / 头像缓存 / 更新检查等）复核确认均已后台执行。配置版本 24 → 25（升级自动重建配置写入新键，用户已改值保留）。</li>
</ul></div>

<div class="version"><h3>v1.6.9.3</h3>"""
old_changelog_head = """<div class="changelog">
<div class="version"><h3>v1.6.9.3</h3>"""
assert old_changelog_head in index, "更新日志头不匹配"
index = index.replace(old_changelog_head, changelog_new)

# 删 v1.6.7.9 段（整块 <div class="version">…</div>）
start = index.find('<div class="version"><h3>v1.6.7.9</h3>')
assert start > 0, "v1.6.7.9 日志段未找到"
end = index.find('</ul></div>', start)
assert end > start
end += len('</ul></div>')
index = index[:start] + index[end:].lstrip("\n")

(OUT / "index.html").write_text(index, encoding="utf-8")
print("index.html 完成：主框 1.6.9.4 / 历史表 +2 行 -2 行 / 日志 +1.6.9.4 段 -1.6.7.9 段")

# ---------------- version.json ----------------

data = json.loads(pathlib.Path("/tmp/web_version.json").read_text(encoding="utf-8"))
headline = (
    "1.6.9.4（4 项）：新增图片卡片背景遮罩样式配置（render.background-style，默认原版灰色遮罩，"
    "改 blur-mask 为模糊遮罩样式——背景先高斯模糊再叠暗色遮罩的毛玻璃观感，参考 EasyBot 液态玻璃模板，"
    "对全部卡片生效）；修复 QQ 群合并转发聊天记录转发到游戏整段展开刷屏（识别后改为单行「[聊天记录 共N条]」占位）；"
    "/kerong reload 可用范围明确（除 QQ 凭据与更新检查需重启外全部即时生效，边界写入配置注释 / 命令反馈 / 教程）；"
    "异步化审查（AI 对话与 reload 移入后台线程，不再阻塞群消息与服务器主线程）"
)
data["version"] = NEW_VERSION
data["changelog"].insert(0, headline)
# 保持 5 条：删最旧（1.6.7.10）
while len(data["changelog"]) > 5:
    data["changelog"].pop()
(OUT / "version.json").write_text(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
print("version.json 完成：版本 1.6.9.4，changelog", len(data["changelog"]), "条")
for i, c in enumerate(data["changelog"]):
    print(f"  [{i}]", c[:40])

# ---------------- tutorial.html ----------------

tutorial = pathlib.Path("/tmp/web_tutorial.html").read_text(encoding="utf-8")

# 1) render 配置块补 background-style 行
old_render_cfg = """  global-opacity: 1.0           # 全部图片卡片的透明度（0.5 = 半透明）
  info-card-builtin-background: true    # 查信息背景：false = 用 img/ 随机图"""
new_render_cfg = """  global-opacity: 1.0           # 全部图片卡片的透明度（0.5 = 半透明）
  background-style: "gray-mask" # 背景遮罩样式：gray-mask = 原版灰色遮罩（默认）；
                               #   blur-mask = 模糊遮罩（毛玻璃背景，文字更突出）
  info-card-builtin-background: true    # 查信息背景：false = 用 img/ 随机图"""
assert old_render_cfg in tutorial, "教程 render 配置块不匹配"
tutorial = tutorial.replace(old_render_cfg, new_render_cfg)

# 2) 背景段补样式说明（接在"想用自己的图片当背景"段后）
old_bg_para = """<p><b>想用自己的图片当背景</b>：把图片（jpg/png）放进 <code>img/</code> 文件夹（插件版 <code>plugins/KERONGPenguin/img/</code>，模组版 <code>kerongpenguin/img/</code>，多张随机轮换），再把对应开关改成 <code>false</code>。img 里没图时自动用回内置背景。</p>"""
new_bg_para = old_bg_para + """
<p><b>背景遮罩样式</b>（1.6.9.4 新增）：<code>background-style: "gray-mask"</code> 是一直以来的原版灰色遮罩；改成 <code>"blur-mask"</code> 后背景会先高斯模糊再叠遮罩，变成柔和的毛玻璃观感（参考主流机器人模板的液态玻璃风格），前景卡片和文字更突出。全部图片卡片统一生效，改完 <code>/kerong reload</code> 即时切换。</p>"""
assert old_bg_para in tutorial, "教程背景段不匹配"
tutorial = tutorial.replace(old_bg_para, new_bg_para)

# 3) 技术细节卡：补 reload 边界一节
old_tech = """<h3>变量获取信息的生效范围与回退规则</h3>"""
new_tech = """<h3>哪些配置不能用 /kerong reload（只能重启服务器）</h3>
<p>绝大多数配置改完执行 <code>/kerong reload</code> 立即生效：命令开关、群列表（bot.groups）、聊天转发格式、进出服播报、消息模板（Markdown 文件）、图片渲染设置（背景样式 / 透明度 / 缓存 / 冷却）、变量获取、AI 参数、签到设置等。<b>只有以下两项 reload 不管用，改完必须重启服务器</b>：</p>
<ul>
<li><b>bot.app-id / bot.secret（QQ 机器人凭据）</b>——QQ 客户端只在启动时用当期凭据连接一次，reload 和 <code>/kerong chs</code> 重连都不会读取新凭据。</li>
<li><b>update-check 段（更新检查）</b>——只在服务器启动后第 8 秒检查一次新版本，改开关 / 镜像地址要下次启动才生效。</li>
</ul>
<p>另：QQ 快捷指令面板的按钮不随 reload 自动更新，改完命令开关后在游戏内执行 <code>/kerong panel</code> 手动重新同步即可（无需重启）。</p>

<h3>变量获取信息的生效范围与回退规则</h3>"""
assert old_tech in tutorial, "技术卡锚点不匹配"
tutorial = tutorial.replace(old_tech, new_tech, 1)

(OUT / "tutorial.html").write_text(tutorial, encoding="utf-8")
print("tutorial.html 完成：render 配置块 + 样式说明 + reload 边界一节")
