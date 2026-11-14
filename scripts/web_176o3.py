#!/usr/bin/env python3
"""1.7.6 二次覆盖轮官网三件幂等更新（version.json / index.html / tutorial.html）。

本轮口径（用户指令）：
  ① 总在线时长「服务器存档数据」源同样作用于在线排行榜——存档时间刻
     用服务器自己的统计文件（QQ号 → 绑定UUID → 服务器UUID 链路），
     插件统计才是绑定记录数据；名片查找健壮化（usercache 同名多条
     从新到旧逐条试）。
  ② 在线排行榜同名玩家（离线服常见）的全部服务器存档账号同时显示。
  ③ 排行榜行时长格式统一「X时X分」；save-data/plugin 源时排行榜时长
     不被变量覆盖。
  ④ 查找全过程写日志。

幂等规则：每步先断言前置态（旧内容在位=待更新 / 新标记在位=已完成），
两者都不满足即报错退出；version.json 保持纯字符串数组。
用法：python3 scripts/web_176o3.py   （读 /tmp/online_* 底稿，写 /tmp/web176o3_out/）
"""
import json
import sys
from pathlib import Path

SRC = Path("/tmp")
OUT = Path("/tmp/web176o3_out")
OUT.mkdir(exist_ok=True)

NEW_MARK = "在线排行榜显示同名玩家"


def step_version_json():
    src = SRC / "online_version.json"
    dst = OUT / "version.json"
    data = json.loads(src.read_text(encoding="utf-8"))
    assert data["version"] == "1.7.6", f"前置失败：version={data['version']}"
    changelog = data["changelog"]
    assert all(isinstance(c, str) for c in changelog), "changelog 必须纯字符串数组"
    if any(NEW_MARK in c for c in changelog):
        print("[跳过] version.json 已是二次覆盖轮内容")
        dst.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return
    # 前置：上一轮（覆盖轮）4 条的第一条含「读不到存档时间刻」
    assert any("读不到存档时间刻" in c for c in changelog), "前置失败：changelog 非覆盖轮内容"
    data["changelog"] = [
        "① 总在线时长「服务器存档数据」源现在同样作用于在线排行榜——存档时间刻始终读服务器自己的统计文件（QQ号 → 绑定UUID → 服务器UUID 链路查找），插件统计才用绑定记录数据；玩家名片查找健壮化（服务器 usercache.json 同名多条从新到旧逐条试，最新一条没有统计文件自动试次新条目）",
        "② 在线排行榜显示同名玩家的全部存档账号——离线服务器常见同名（改名前的旧账号 / 大小写变体 / 多个同名玩家），同一名字命中的每个服务器 UUID 各占一行同时显示；存档一条都读不到的玩家回退插件统计时长",
        "③ 在线排行榜行时长格式统一「X时X分」——与玩家名片三个获取源同口径（旧「X小时X分钟」写法停用）；获取源选存档 / 插件统计时排行榜时长不再被变量覆盖（默认变量源保持变量优先）",
        "④ 存档查找全过程（每个玩家的候选 UUID / 命中明细 / 回退去向 / 排行榜汇总）写入 logs/qq 日志文件的「[存档统计]」行，排障直接对照服务器 stats 目录与 usercache.json",
    ]
    dst.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # 结构断言
    check = json.loads(dst.read_text(encoding="utf-8"))
    assert check["version"] == "1.7.6" and len(check["changelog"]) == 4
    assert all(isinstance(c, str) and NEW_MARK in c or isinstance(c, str) for c in check["changelog"])
    print("[完成] version.json → 4 条二次覆盖轮")


NEW_LIS = [
    "<li>① 总在线时长「服务器存档数据」源现在同样作用于在线排行榜——存档时间刻始终读服务器自己的统计文件（QQ号 → 绑定UUID → 服务器UUID 链路查找），插件统计才用绑定记录数据；玩家名片查找健壮化（服务器 usercache.json 同名多条从新到旧逐条试，最新一条没有统计文件自动试次新条目）</li>",
    "<li>② 在线排行榜显示同名玩家的全部存档账号——离线服务器常见同名（改名前的旧账号 / 大小写变体 / 多个同名玩家），同一名字命中的每个服务器 UUID 各占一行同时显示；存档一条都读不到的玩家回退插件统计时长</li>",
    "<li>③ 在线排行榜行时长格式统一「X时X分」——与玩家名片三个获取源同口径（旧「X小时X分钟」写法停用）；获取源选存档 / 插件统计时排行榜时长不再被变量覆盖（默认变量源保持变量优先）</li>",
    "<li>④ 存档查找全过程（每个玩家的候选 UUID / 命中明细 / 回退去向 / 排行榜汇总）写入 logs/qq 日志文件的「[存档统计]」行，排障直接对照服务器 stats 目录与 usercache.json</li>",
]


def step_index():
    src = SRC / "online_index.html"
    dst = OUT / "index.html"
    text = src.read_text(encoding="utf-8")
    if NEW_MARK in text:
        print("[跳过] index.html 已是二次覆盖轮内容")
        dst.write_text(text, encoding="utf-8")
        return
    # 前置：v1.7.6 块为覆盖轮 4 条（锚：第一条含「读不到存档时间刻」）
    assert "读不到存档时间刻" in text, "前置失败：index 无覆盖轮 v1.7.6 块"
    # v1.7.6 块的 4 个 li 整体替换（块内逐条定位）
    anchor_old = '<div class="version"><h3>v1.7.6</h3><span class="date">2026-11-14</span><ul>'
    assert anchor_old in text, "前置失败：v1.7.6 块锚不存在"
    # 旧 4 条 li（覆盖轮内容）逐条替换为新 4 条
    olds = [
        "<li>① 修复：总在线时长「服务器存档数据」源读不到存档时间刻的问题——统计文件查找改用服务器自己的玩家 UUID（绑定记录的 UUID 找不到文件时，自动改用服务器 usercache.json 记录的该玩家 UUID 重查，改名 / 老绑定数据也能读到），查找全过程（目录 / UUID 来源 / 文件 / 刻数）写入日志文件；三种获取源的展示格式统一为「X时X分」（配置版本 52 → 53）</li>",
        "<li>② 修复：执行命令只显示命令输出、缺少命令日志的问题——回复开头统一带「已执行命令：…」行，群里看得出执行了什么命令（无输出时同样带命令行）</li>",
        "<li>③ 修复：消息模板（含菜单模板）可靠释放与升级——messages 段配了自定义模板文件名（如 help/menu.md）时同样释放默认模板文件作为起点（此前不释放，改配置无效果）；模板升级判定收紧为逐版精确比对，自己改过的模板内容绝不会被自动更新覆盖；配置文件注释全面简化，补齐图片渲染背景选择缺注释的键</li>",
        "<li>④ 移除：真实玻璃渲染测试功能（render.real-glass）整体下线，恢复原版毛玻璃渲染（配置键升级自动删除）；官网教程「邀请机器人进群」步骤补充说明：机器人设置必须由群主操作</li>",
    ]
    assert all(o in text for o in olds), "前置失败：覆盖轮 4 条 li 不全在位"
    for old, new in zip(olds, NEW_LIS):
        text = text.replace(old, new, 1)
    dst.write_text(text, encoding="utf-8")
    out = dst.read_text(encoding="utf-8")
    assert NEW_MARK in out and all(new in out for new in NEW_LIS)
    assert out.count('<h3>v1.7.6</h3>') == 1 and "读不到存档时间刻" not in out
    print("[完成] index.html v1.7.6 日志块 → 二次覆盖轮 4 条")


def step_tutorial():
    src = SRC / "online_tutorial.html"
    dst = OUT / "tutorial.html"
    text = src.read_text(encoding="utf-8")
    if "每个存档账号" in text:  # 教程侧幂等标记（同名多账号口径文案）
        print("[跳过] tutorial.html 已是二次覆盖轮内容")
        dst.write_text(text, encoding="utf-8")
        return
    # ---- 1. /在线排行榜 命令表条目 ----
    old_cmd = "<tr><td><code>/在线排行榜</code></td><td>全服在线时长排行榜（图片分页展示）</td></tr>"
    new_cmd = ("<tr><td><code>/在线排行榜</code></td><td>全服在线时长排行榜（图片分页展示）。"
               "总在线时长获取源选「服务器存档数据」时：按存档统计排序，同名玩家（离线服务器常见——"
               "改名前的旧账号 / 多个同名玩家）的每个存档账号各占一行同时显示；时长格式与玩家名片统一「X时X分」</td></tr>")
    assert old_cmd in text, "前置失败：/在线排行榜 命令表条目不存在"
    text = text.replace(old_cmd, new_cmd, 1)

    # ---- 2. 获取源 cfg-item：save-data 段落更新（作用范围 + 名片链路 + 排行榜）----
    old_sd = ("<p>・<code>save-data</code>（服务器存档数据）：读主世界 <code>stats/&lt;uuid&gt;.json</code> 里的 "
              "<code>minecraft:play_time</code>（游戏刻，72000 刻 = 1 小时）。查找用<b>服务器自己的玩家 UUID</b>："
              "先用绑定记录的 UUID 找统计文件，找不到自动改用服务器 <code>usercache.json</code> 里记录的该玩家 UUID 重查"
              "（改名 / 老绑定数据 UUID 对不上也能读到）；查找的每一环（目录 / UUID 来源 / 文件 / 刻数）都写进日志文件"
              "（<code>logs/qq/</code> 的「[存档统计]」行），排障可直接对照。适合装插件之前玩家就一直在玩、插件自记录时长偏小的服务器；"
              "<b>读取不到自动回退</b>变量（若配置）→ 插件统计。</p>")
    new_sd = ("<p>・<code>save-data</code>（服务器存档数据）：读主世界 <code>stats/&lt;uuid&gt;.json</code> 里的 "
              "<code>minecraft:play_time</code>（游戏刻，72000 刻 = 1 小时），<b>玩家名片与在线排行榜共用此源</b>。"
              "查找用<b>服务器自己的玩家 UUID</b>（插件统计才用绑定记录里的数据）：按 QQ 号找到绑定的 UUID 先找统计文件，"
              "找不到自动改用服务器 <code>usercache.json</code> 里该玩家的 UUID 重查——同名多条从新到旧逐条试"
              "（最新一条登录过但没进过世界没有统计文件时，自动试次新条目）。在线排行榜会把同名玩家的每个存档账号"
              "（离线服务器常见：改名前的旧账号 / 多个同名玩家）各占一行同时显示；存档一条都读不到的玩家回退插件统计时长。"
              "查找的每一环（候选 UUID / 命中明细 / 回退去向）都写进日志文件（<code>logs/qq/</code> 的「[存档统计]」行），"
              "排障可直接对照。适合装插件之前玩家就一直在玩、插件自记录时长偏小的服务器；玩家名片<b>读取不到自动回退</b>"
              "变量（若配置）→ 插件统计。</p>")
    assert old_sd in text, "前置失败：save-data 段落不存在"
    text = text.replace(old_sd, new_sd, 1)

    # ---- 3. variable 段落补排行榜口径 ----
    old_var = ("<p>・<code>variable</code>（变量，默认）：填了 <code>total-playtime</code> 变量就用变量，"
               "没填 / 读取失败回退插件统计——与历史版本行为完全一致。</p>")
    new_var = ("<p>・<code>variable</code>（变量，默认）：填了 <code>total-playtime</code> 变量就用变量，"
               "没填 / 读取失败回退插件统计——与历史版本行为完全一致（在线排行榜的时长同样变量优先；"
               "选存档 / 插件统计源时排行榜时长不再被变量覆盖）。</p>")
    assert old_var in text, "前置失败：variable 段落不存在"
    text = text.replace(old_var, new_var, 1)

    # ---- 4. 获取源 cfg-item 标题句更新（作用范围点名排行榜）----
    old_scope = "<p>选择玩家名片卡片「在线时长」项的数据来源，三种取值（也接受中文「存档数据 / 插件统计 / 变量」），<b>三种源的展示格式统一为「X时X分」</b>：</p>"
    new_scope = "<p>选择总在线时长的数据来源（<b>玩家名片「在线时长」项 + 在线排行榜</b>），三种取值（也接受中文「存档数据 / 插件统计 / 变量」），<b>三种源的展示格式统一为「X时X分」</b>：</p>"
    assert old_scope in text, "前置失败：获取源标题句不存在"
    text = text.replace(old_scope, new_scope, 1)

    dst.write_text(text, encoding="utf-8")
    out = dst.read_text(encoding="utf-8")
    assert new_cmd in out and new_sd in out and new_var in out and new_scope in out
    assert "每个存档账号" in out  # 同名多账号口径标记
    print("[完成] tutorial.html → 命令表 + 获取源 cfg-item 四处更新")


if __name__ == "__main__":
    step_version_json()
    step_index()
    step_tutorial()
    print("\n全部完成：产物在 /tmp/web176o3_out/")
