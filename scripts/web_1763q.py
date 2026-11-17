#!/usr/bin/env python3
"""官网三件同步 1.7.6.3 三次覆盖轮（提示词技能引导 + 纯表情包空白消息修复）。"""
import json
import os

REPO = "/home/z/my-project/repo"

# ---------- 1. version.json：changelog 头部插入两条 ----------
vj_path = os.path.join(REPO, "website/version.json")
with open(vj_path, encoding="utf-8") as f:
    vj = json.load(f)

new_items = [
    "⑥ 提示词默认带技能引导：新装默认模板新增「技能」段（列出 AI 拥有的技能 + 遇到不会 / 没把握 / 时效性问题先搜索再回答，不凭记忆猜测）；对话内系统技能段同步重写为「你拥有的技能如下」，立即生效（已有提示词文件不覆盖，可参考新默认模板自行补段）",
    "⑦ 修复：AI 只发一个表情包时不再先发一条空白的「[AI]」消息——直接发表情包图片；表情没匹配到图片或图片发送失败时发一句文字说明兜底；游戏内对话同步修复",
]
changelog = vj["changelog"]
# 原有条目 ①-⑤ 保持，追加 ⑥⑦
vj["changelog"] = changelog + new_items
with open(vj_path, "w", encoding="utf-8") as f:
    json.dump(vj, f, ensure_ascii=False, indent=2)
    f.write("\n")
print("version.json 更新完成（changelog 追加 ⑥⑦）")

# ---------- 2. index.html：v1.7.6.3 日志块追加两条 li ----------
idx_path = os.path.join(REPO, "website/index.html")
with open(idx_path, encoding="utf-8") as f:
    html = f.read()

anchor = (
    "<li>调整：配置注释精简且<b>全键注释覆盖</b>（每个配置键都有注释）；恢复 ai.memes.max-per-reply / "
    "ai.crawler.timeout-seconds / ai.crawler.max-chars 三键；config-version 59 → 60（升级自动收敛：读取上限旧默认 2/3 自动升 8，自定义值不动）</li>"
)
assert anchor in html, "index.html v1.7.6.3 块锚点未找到"
addition = (
    anchor
    + "\n<li>新增：AI 提示词默认带<b>技能引导</b>——新装默认模板新增「技能」段（AI 拥有的技能列表 + 「遇到不会、没把握或时效性的问题，先搜索再回答，不要凭记忆猜测，也不要直接说不知道」）；对话内系统技能段同步重写为「你拥有的技能如下」，动态注入立即生效（已有提示词文件不覆盖，可参考新默认模板自行补段）</li>"
    + "\n<li>修复：AI 只发一个表情包时不再先发一条只有「[AI]」前缀的空白消息——直接发表情包图片；表情没匹配到图片（可检查表情包文件夹）或图片发送失败时发一句文字说明兜底；游戏内对话同步修复（不再广播空白）</li>"
)
html = html.replace(anchor, addition, 1)
with open(idx_path, "w", encoding="utf-8") as f:
    f.write(html)
print("index.html 更新完成（v1.7.6.3 块追加两条）")

# ---------- 3. tutorial.html：提示词条目补技能段说明 + 目录树注释微调 ----------
tut_path = os.path.join(REPO, "website/tutorial.html")
with open(tut_path, encoding="utf-8") as f:
    tut = f.read()

old_tree = "├── 提示词.md           AI 提示词（角色设定 + 专属知识）"
new_tree = "├── 提示词.md           AI 提示词（角色设定 + 技能 + 专属知识）"
assert old_tree in tut, "tutorial 目录树锚点未找到"
tut = tut.replace(old_tree, new_tree, 1)

old_para = (
    "「角色设定」写身份与回复风格（如「你是一个友好的游戏群助手，请简洁回答。」），"
    "「专属知识」写服务器规则、玩法说明、常见问题等资料，AI 对话时都会参考。"
)
new_para = (
    "「角色设定」写身份与回复风格（如「你是一个友好的游戏群助手，请简洁回答。」），"
    "「技能」段列出 AI 拥有的技能并写明「遇到不会、没把握或时效性的问题（新闻、新版本、价格等），"
    "先搜索再回答，不要凭记忆猜测，也不要直接说不知道」（新装默认模板自带，对话内系统还会按配置"
    "自动注入「系统技能」段，以系统注入为准），「专属知识」写服务器规则、玩法说明、常见问题等资料，"
    "AI 对话时都会参考。"
)
assert old_para in tut, "tutorial 提示词条目锚点未找到"
tut = tut.replace(old_para, new_para, 1)

with open(tut_path, "w", encoding="utf-8") as f:
    f.write(tut)
print("tutorial.html 更新完成（目录树 + 提示词条目技能段说明）")
