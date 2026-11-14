#!/usr/bin/env python3
"""官网三件 1.7.6 三次覆盖轮更新（幂等：目标态已达成则跳过；锚点不匹配报错）。

同号覆盖：version.json 与 index.html 的 v1.7.6 条目整组替换为三次覆盖轮
口径；tutorial.html 改命令表 /在线排行榜 描述与「总在线时长获取源」
cfg-item（默认值 variable → save-data、排行榜直枚举不依赖绑定）。
底稿 = 仓库 website/ 副本（已与线上 md5 一致核对）。

⚠ 幂等跳过标记按文件各选（坑表）：version 用「排行榜直枚举」、index 用
「不再先经 QQ 绑定」、tutorial 用「不需要开 QQ 绑定」。
"""
import json
import sys
from pathlib import Path

WEB = Path("/home/z/my-project/penguin-git/website")

CHANGLEOG = [
    "① 在线排行榜「服务器存档数据」源改为直接枚举服务器自己的全部玩家数据——不再先经 QQ 绑定记录，没开 QQ 绑定的服务器也能出榜；usercache.json 全量玩家逐个读存档统计，usercache 条目被服务器清理但统计文件还在的老玩家用绑定记录补名字照常上榜；同名玩家（改名前的旧账号 / 多个同名）每个存档账号各占一行同时显示",
    "② 总在线时长获取源默认改为「服务器存档数据」（save-data）——玩家名片读取不到自动回退变量 → 插件统计；在线排行榜直接枚举服务器数据（不依赖 QQ 绑定）；旧配置升级自动收敛为存档源（自行选过插件统计 / 变量的不动），config-version 53 → 54",
    "③ 修复 bot.groups 群列表只写一对双引号 \"\"（或纯空白 / 纯引号）被误判为已填写——现在视同未填写：自动绑定正常触发、群白名单不再恒不匹配",
    "④ 排行榜存档收集汇总写日志（logs/qq 的「[存档统计] 排行榜直枚举」行：usercache 命中数 / stats 目录补充数 / 无名字可补跳过数），排障直接对照",
]

INDEX_BLOCK = """<div class="version"><h3>v1.7.6</h3><span class="date">2026-11-15</span><ul>
<li>① 在线排行榜「服务器存档数据」源改为直接枚举服务器自己的全部玩家数据——不再先经 QQ 绑定记录，没开 QQ 绑定的服务器也能出榜；usercache.json 全量玩家逐个读存档统计，usercache 条目被服务器清理但统计文件还在的老玩家用绑定记录补名字照常上榜；同名玩家（改名前的旧账号 / 多个同名）每个存档账号各占一行同时显示</li>
<li>② 总在线时长获取源默认改为「服务器存档数据」（save-data）——玩家名片读取不到自动回退变量 → 插件统计；在线排行榜直接枚举服务器数据（不依赖 QQ 绑定）；旧配置升级自动收敛为存档源（自行选过插件统计 / 变量的不动），config-version 53 → 54</li>
<li>③ 修复 bot.groups 群列表只写一对双引号 ""（或纯空白 / 纯引号）被误判为已填写——现在视同未填写：自动绑定正常触发、群白名单不再恒不匹配</li>
<li>④ 排行榜存档收集汇总写日志（logs/qq 的「[存档统计] 排行榜直枚举」行：usercache 命中数 / stats 目录补充数 / 无名字可补跳过数），排障直接对照</li>
</ul></div>"""

TUT_CMD_OLD = "<tr><td><code>/在线排行榜</code></td><td>全服在线时长排行榜（图片分页展示）。总在线时长获取源选「服务器存档数据」时：按存档统计排序，同名玩家（离线服务器常见——改名前的旧账号 / 多个同名玩家）的每个存档账号各占一行同时显示；时长格式与玩家名片统一「X时X分」</td></tr>"
TUT_CMD_NEW = "<tr><td><code>/在线排行榜</code></td><td>全服在线时长排行榜（图片分页展示）。总在线时长获取源选「服务器存档数据」时：直接读服务器自己的全部玩家存档统计（不需要开 QQ 绑定），同名玩家（离线服务器常见——改名前的旧账号 / 多个同名玩家）的每个存档账号各占一行同时显示；时长格式与玩家名片统一「X时X分」</td></tr>"

TUT_SNIPPET_OLD = '<pre>variable-fetch:\n  total-playtime-source: "variable"  # save-data 存档数据 / plugin 插件统计 / variable 变量</pre>'
TUT_SNIPPET_NEW = '<pre>variable-fetch:\n  total-playtime-source: "save-data"  # 默认存档数据；plugin 插件统计 / variable 变量</pre>'

TUT_SAVEDATA_OLD = "<p>・<code>save-data</code>（服务器存档数据）：读主世界 <code>stats/&lt;uuid&gt;.json</code> 里的 <code>minecraft:play_time</code>（游戏刻，72000 刻 = 1 小时），<b>玩家名片与在线排行榜共用此源</b>。查找用<b>服务器自己的玩家 UUID</b>（插件统计才用绑定记录里的数据）：按 QQ 号找到绑定的 UUID 先找统计文件，找不到自动改用服务器 <code>usercache.json</code> 里该玩家的 UUID 重查——同名多条从新到旧逐条试（最新一条登录过但没进过世界没有统计文件时，自动试次新条目）。在线排行榜会把同名玩家的每个存档账号（离线服务器常见：改名前的旧账号 / 多个同名玩家）各占一行同时显示；存档一条都读不到的玩家回退插件统计时长。查找的每一环（候选 UUID / 命中明细 / 回退去向）都写进日志文件（<code>logs/qq/</code> 的「[存档统计]」行），排障可直接对照。适合装插件之前玩家就一直在玩、插件自记录时长偏小的服务器；玩家名片<b>读取不到自动回退</b>变量（若配置）→ 插件统计。</p>"
TUT_SAVEDATA_NEW = "<p>・<code>save-data</code>（服务器存档数据，<b>默认</b>）：读主世界 <code>stats/&lt;uuid&gt;.json</code> 里的 <code>minecraft:play_time</code>（游戏刻，72000 刻 = 1 小时），<b>玩家名片与在线排行榜共用此源，但两条链路各走各的</b>——<b>玩家名片</b>按 QQ 号 → 绑定 UUID → 服务器 UUID 查找：绑定的 UUID 先找统计文件，找不到自动改用服务器 <code>usercache.json</code> 里该玩家的 UUID 重查（同名多条从新到旧逐条试，最新一条登录过但没进过世界没有统计文件时自动试次新条目），读取不到回退变量（若配置）→ 插件统计；<b>在线排行榜</b>直接枚举服务器自己的全部玩家数据（<b>不需要开 QQ 绑定</b>）：usercache.json 全量玩家逐个读存档统计，同名玩家（离线服务器常见：改名前的旧账号 / 多个同名玩家）每个存档账号各占一行同时显示，usercache 条目被服务器清理但统计文件还在的老玩家用绑定记录补名字照常上榜，绑定玩家在存档里一条都没有时回退插件统计时长。查找过程写进日志文件（<code>logs/qq/</code> 的「[存档统计]」行），排障可直接对照。适合装插件之前玩家就一直在玩、插件自记录时长偏小的服务器。</p>"

TUT_VARIABLE_OLD = "<p>・<code>variable</code>（变量，默认）：填了 <code>total-playtime</code> 变量就用变量，没填 / 读取失败回退插件统计——与历史版本行为完全一致（在线排行榜的时长同样变量优先；选存档 / 插件统计源时排行榜时长不再被变量覆盖）。</p>"
TUT_VARIABLE_NEW = "<p>・<code>variable</code>（变量）：填了 <code>total-playtime</code> 变量就用变量，没填 / 读取失败回退插件统计——与历史版本行为完全一致（在线排行榜的时长同样变量优先；选存档 / 插件统计源时排行榜时长不再被变量覆盖）。1.7.6 三次覆盖轮起默认源从变量改为存档数据，想保持旧行为把本键改回 <code>variable</code> 即可。</p>"


def main():
    errors = []

    # ---------- version.json ----------
    vp = WEB / "version.json"
    data = json.loads(vp.read_text(encoding="utf-8"))
    if any("排行榜直枚举" in c for c in data["changelog"]):
        print("version.json：三次覆盖轮条目已在位，跳过")
    else:
        if data.get("version") != "1.7.6":
            errors.append(f"version.json 版本非 1.7.6: {data.get('version')}")
        if len(data["changelog"]) != 4 or "服务器存档数据」源现在同样作用于在线排行榜" not in data["changelog"][0]:
            errors.append("version.json 前置态不匹配（应为二次覆盖轮 4 条）")
        if not errors:
            data["changelog"] = CHANGLEOG
            vp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            # 结构断言：纯字符串数组 + 版本号
            check = json.loads(vp.read_text(encoding="utf-8"))
            assert check["version"] == "1.7.6"
            assert all(isinstance(c, str) for c in check["changelog"]) and len(check["changelog"]) == 4
            print("version.json：已更新为三次覆盖轮 4 条")

    # ---------- index.html ----------
    ip = WEB / "index.html"
    text = ip.read_text(encoding="utf-8")
    if "不再先经 QQ 绑定" in text:
        print("index.html：三次覆盖轮块已在位，跳过")
    else:
        # 旧 v1.7.6 块整组替换（锚：v1.7.6 头到 </ul></div>）
        start = text.find('<div class="version"><h3>v1.7.6</h3>')
        end = text.find("</ul></div>", start)
        if start < 0 or end < 0:
            errors.append("index.html v1.7.6 块定位失败")
        else:
            old_block = text[start:end + len("</ul></div>")]
            if "服务器存档数据」源现在同样作用于在线排行榜" not in old_block:
                errors.append("index.html v1.7.6 块前置态不匹配（应为二次覆盖轮）")
            else:
                text = text.replace(old_block, INDEX_BLOCK)
                # 主下载框发布日期 + 历史表 1.7.6 四行日期 → 2026-11-15
                text = text.replace(
                    '<p style="color:#7a848e;margin-bottom:20px">发布于 2026-11-14</p>',
                    '<p style="color:#7a848e;margin-bottom:20px">发布于 2026-11-15</p>')
                for jar in ("NeoForge 1.7.6", "Spigot 1.7.6", "Fabric 1.7.6（1.20.4 通用包）", "Fabric 1.7.6（1.20.1）"):
                    text = text.replace(f"<tr><td>{jar}</td><td>2026-11-14</td>",
                                        f"<tr><td>{jar}</td><td>2026-11-15</td>")
                ip.write_text(text, encoding="utf-8")
                print("index.html：v1.7.6 块重写 + 日期更新")

    # ---------- tutorial.html ----------
    tp = WEB / "tutorial.html"
    ttext = tp.read_text(encoding="utf-8")
    if "不需要开 QQ 绑定" in ttext:
        print("tutorial.html：三次覆盖轮条目已在位，跳过")
    else:
        for old, new, name in (
            (TUT_CMD_OLD, TUT_CMD_NEW, "命令表 /在线排行榜"),
            (TUT_SNIPPET_OLD, TUT_SNIPPET_NEW, "获取源 YAML 片段"),
            (TUT_SAVEDATA_OLD, TUT_SAVEDATA_NEW, "save-data 段"),
            (TUT_VARIABLE_OLD, TUT_VARIABLE_NEW, "variable 段"),
        ):
            if old not in ttext:
                errors.append(f"tutorial.html {name} 前置态锚点未命中")
            else:
                ttext = ttext.replace(old, new)
        if not errors:
            tp.write_text(ttext, encoding="utf-8")
            print("tutorial.html：命令表 + 获取源 cfg-item 四处更新")

    if errors:
        print("\n".join(f"ERROR: {e}" for e in errors))
        sys.exit(1)

    # ---------- 终态复核 ----------
    v = json.loads((WEB / "version.json").read_text(encoding="utf-8"))
    assert v["version"] == "1.7.6" and len(v["changelog"]) == 4
    i = (WEB / "index.html").read_text(encoding="utf-8")
    assert i.count('<div class="version"><h3>v1.7.6</h3>') == 1
    assert "发布于 2026-11-15" in i and "v1.7.6</h3><span class=\"date\">2026-11-15" in i
    assert i.count("2026-11-15") >= 5  # 主框 + 日志块 + 历史表 4 行
    t = (WEB / "tutorial.html").read_text(encoding="utf-8")
    assert "不需要开 QQ 绑定" in t and 'total-playtime-source: "save-data"' in t
    assert "（变量，默认）" not in t
    print("终态复核全部通过")


if __name__ == "__main__":
    main()
