#!/usr/bin/env python3
"""1.7.6.1 官网三件套幂等更新：version.json / index.html / tutorial.html。

本轮（新版本 1.7.6.1，用户口径「修复在线排行榜假人插件的假人时长也在
排行榜里面的问题」+「给配置文件添加一个配置，打开的时候查在线额外显示
一个假人列表，显示当前在线的假人」）：
  - version.json：版本 1.7.6.1 + 本轮 4 条（纯字符串数组）；
  - index.html：主下载框版本与四按钮 → 1.7.6.1；历史表置顶 4 行；更新
    日志置顶 v1.7.6.1 块（4 条要点）；
  - tutorial.html：/查在线 与 /在线排行榜 命令行补假人说明；Fabric 假人
    白名单 cfg-item 改全端口径；新增「查在线假人列表」cfg-item。
底稿 = 仓库 website/ 副本（已与线上 md5 一致核对）。

⚠ 幂等跳过标记按文件各选（坑表）：version 用「online-list.show-fake-players」、index 用
「v1.7.6.1」、tutorial 用「查在线假人列表」。
"""
import json
import sys
from pathlib import Path

WEB = Path("/home/z/my-project/penguin-git/website")

CHANGELOG = [
    "① 修复在线排行榜计入假人插件假人时长的问题——假人插件（如 minecraft-fakeplayer）注入的假人不再出现在总在线时长排行榜：连接地址为空 / 回环 / 内存连接的假人自动识别登记（插件数据目录 fake-players.json，离线假人的历史时长同样排除），真人用真实外网连接进服自动恢复上榜；两个数据源（服务器存档 / 插件统计）都过滤，过滤计数写日志",
    "② 新增配置 online-list.show-fake-players（默认关）：开启后 /查在线 的主列表只显示真实玩家，底部额外显示「假人在线」小节，列出当前在线的假人；关闭时全部混排与旧版一致；文字列表（markdown）模式同开关同口径——人数只算真实玩家，末尾附假人摘要行",
    "③ 假人白名单 qq-bind.fake-player-whitelist 升级为全端生效——Fabric 端跳过未绑定限制照旧，所有端都用它识别假人（不计入排行榜 / 查在线划入假人小节），大小写不敏感",
    "④ 配置版本 config-version 54 → 55：升级自动补 online-list.show-fake-players 新键（默认关，已改值不动）",
]

INDEX_BLOCK = """<div class="version"><h3>v1.7.6.1</h3><span class="date">2026-11-15</span><ul>
<li>修复：在线排行榜不再计入假人插件（minecraft-fakeplayer 等）假人的时长——本地注入连接（地址为空 / 回环 / 内存连接）自动识别登记，离线假人的历史时长同样排除；真人以真实外网连接进服自动恢复上榜；假人白名单（qq-bind.fake-player-whitelist）命中的同样生效</li>
<li>新增：查在线假人列表开关 online-list.show-fake-players（默认关）——开启后主列表只显示真实玩家，底部额外显示「假人在线」小节列出当前在线的假人；文字列表模式同开关同口径（人数只算真实玩家 + 末尾假人摘要行）</li>
<li>调整：假人白名单 qq-bind.fake-player-whitelist 全端生效——Fabric 端跳过未绑定限制照旧，所有端都用它识别假人</li>
<li>配置版本 54 → 55，升级自动补新键（已改值不动）</li>
</ul></div>
"""

INDEX_HISTORY_ROWS = """<tr><td>NeoForge 1.7.6.1</td><td>2026-11-15</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.6.1.jar">下载</a></td></tr>
<tr><td>Spigot 1.7.6.1</td><td>2026-11-15</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.6.1.jar">下载</a></td></tr>
<tr><td>Fabric 1.7.6.1（1.20.4 通用包）</td><td>2026-11-15</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.6.1.jar">下载</a></td></tr>
<tr><td>Fabric 1.7.6.1（1.20.1 独立包）</td><td>2026-11-15</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.6.1.jar">下载</a></td></tr>
"""

TUTORIAL_FAKE_CFG = """<div class="cfg-item">
<h4>查在线假人列表 <span class="key">online-list.show-fake-players</span></h4>
<div class="config">
<pre>online-list:
  show-fake-players: false  # true = 主列表只显示真实玩家，底部额外显示「假人在线」小节</pre>
</div>
<p>1.7.6.1 新增，默认关。开启后 /查在线（图片与文字列表同口径）的主列表与人数只算真实玩家，底部额外列出当前在线的假人插件假人；关闭时全部混排与旧版一致。判定口径：登录连接为空 / 回环 / 内存连接（假人插件本地注入），或下方假人白名单名单命中。同口径的假人也不再计入在线排行榜（含下线假人的历史时长）。改完 <code>/hbkrg reload</code> 即时生效。</p>
</div>
"""

TUTORIAL_WL_OLD = """<h4>Fabric 假人白名单 <span class="key">qq-bind.fake-player-whitelist</span></h4>
<div class="config">
<pre>qq-bind:
  fake-player-whitelist: []  # 假人玩家名列表，如 ["假人1", "Bot_02"]</pre>
</div>
<p>Fabric 模组版专属（1.7.6 新增）：假人插件（minecraft-fakeplayer 等）注入的假人走本地连接（内存 / 回环），<b>自动</b>豁免未绑定限制——不锁移动、不输出绑定码、不会被超时踢出，真实玩家不受影响（与 Spigot / NeoForge 版口径一致）。个别假人模组走真实网络连接检测不到时，把假人的名字填进这个名单即可（大小写不敏感）。改完 <code>/hbkrg reload</code> 即时生效。</p>"""
TUTORIAL_WL_NEW = """<h4>假人白名单 <span class="key">qq-bind.fake-player-whitelist</span></h4>
<div class="config">
<pre>qq-bind:
  fake-player-whitelist: []  # 假人玩家名列表，如 ["假人1", "Bot_02"]</pre>
</div>
<p>1.7.6 新增（Fabric 端）、1.7.6.1 起全端生效。假人插件（minecraft-fakeplayer 等）注入的假人走本地连接（内存 / 回环），<b>自动</b>识别——Fabric 端豁免未绑定限制（不锁移动、不输出绑定码、不会被超时踢出），所有端都不计入在线排行榜、/查在线 开启假人列表后划入「假人在线」小节，真实玩家不受影响。个别假人模组走真实网络连接检测不到时，把假人的名字填进这个名单即可（大小写不敏感）。改完 <code>/hbkrg reload</code> 即时生效。</p>"""

errors = []


def main():
    # ---------- version.json ----------
    vp = WEB / "version.json"
    data = json.loads(vp.read_text(encoding="utf-8"))
    if data.get("version") == "1.7.6.1" and any("online-list.show-fake-players" in c for c in data["changelog"]):
        print("version.json：1.7.6.1 条目已在位，跳过")
    else:
        if data.get("version") != "1.7.6":
            errors.append(f"version.json 版本非 1.7.6: {data.get('version')}")
        if len(data["changelog"]) != 4 or not any("排行榜直枚举" in c for c in data["changelog"]):
            errors.append("version.json 前置态不匹配（应为 1.7.6 三次覆盖轮 4 条）")
        if not errors:
            data["version"] = "1.7.6.1"
            data["changelog"] = CHANGELOG
            vp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            check = json.loads(vp.read_text(encoding="utf-8"))
            assert check["version"] == "1.7.6.1"
            assert all(isinstance(c, str) for c in check["changelog"]) and len(check["changelog"]) == 4
            print("version.json：已更新为 1.7.6.1 + 4 条")

    # ---------- index.html ----------
    ip = WEB / "index.html"
    text = ip.read_text(encoding="utf-8")
    if "v1.7.6.1" in text:
        print("index.html：1.7.6.1 块已在位，跳过")
    else:
        ok = True
        # 主下载框版本 + 四按钮
        if "版本 1.7.6</p>" not in text:
            errors.append("index 主框版本行未找到"); ok = False
        for name in ("Spigot-1.7.6.jar", "NeoForge-1.20.4-1.7.6.jar",
                     "Fabric-1.20.4-1.7.6.jar", "Fabric-1.20.1-1.7.6.jar"):
            if name not in text:
                errors.append(f"index 按钮 {name} 未找到"); ok = False
        # 历史表锚点（v1.7.6 行组之前插入）
        anchor_history = "<tr><td>NeoForge 1.7.6</td>"
        # 更新日志锚点（v1.7.6 块之前插入）
        anchor_log = '<div class="version"><h3>v1.7.6</h3>'
        if anchor_history not in text or anchor_log not in text:
            errors.append("index 历史表/日志锚点未找到"); ok = False
        if ok and not errors:
            text = text.replace("版本 1.7.6</p>", "版本 1.7.6.1</p>", 1)
            # ⚠ 只替换主下载框四个按钮的 href（class="dl-btn" 上下文锚定），
            # 不能全文件替换——旧 v1.7.6 历史行的 JAR 链接必须保持 1.7.6.jar
            for old, new in (
                ('KERONGPenguin_Spigot-1.7.6.jar" class="dl-btn"', 'KERONGPenguin_Spigot-1.7.6.1.jar" class="dl-btn"'),
                ('KERONGPenguin_NeoForge-1.20.4-1.7.6.jar" class="dl-btn"', 'KERONGPenguin_NeoForge-1.20.4-1.7.6.1.jar" class="dl-btn"'),
                ('KERONGPenguin_Fabric-1.20.4-1.7.6.jar" class="dl-btn"', 'KERONGPenguin_Fabric-1.20.4-1.7.6.1.jar" class="dl-btn"'),
                ('KERONGPenguin_Fabric-1.20.1-1.7.6.jar" class="dl-btn"', 'KERONGPenguin_Fabric-1.20.1-1.7.6.1.jar" class="dl-btn"'),
            ):
                assert old in text, old[:50]
                text = text.replace(old, new, 1)
            text = text.replace(anchor_history, INDEX_HISTORY_ROWS + anchor_history, 1)
            text = text.replace(anchor_log, INDEX_BLOCK + anchor_log, 1)
            ip.write_text(text, encoding="utf-8")
            # 后验：三处版本号 + 四个新链接
            out = ip.read_text(encoding="utf-8")
            assert "版本 1.7.6.1</p>" in out
            assert out.count("v1.7.6.1") == 1
            for name in ("Spigot-1.7.6.1.jar", "NeoForge-1.20.4-1.7.6.1.jar",
                         "Fabric-1.20.4-1.7.6.1.jar", "Fabric-1.20.1-1.7.6.1.jar"):
                assert name in out, name
            # 主框按钮与历史行各出现一次 = 每名 2 次
            assert out.count("KERONGPenguin_Spigot-1.7.6.1.jar") == 2
            print("index.html：主框/历史表/更新日志已更新")

    # ---------- tutorial.html ----------
    tp = WEB / "tutorial.html"
    ttext = tp.read_text(encoding="utf-8")
    if "查在线假人列表" in ttext:
        print("tutorial.html：假人列表条目已在位，跳过")
    else:
        tok = True
        # 1) 新 cfg-item：插在 markdown.queryOnline 条目之后（查在线输出段内）
        anchor_cfg = """<div class="cfg-item">
<h4>查在线模板文件名 <span class="key">markdown.queryOnline</span></h4>
<div class="config">
<pre>markdown:
  queryOnline: "online.md"  # 查在线文字列表的模板文件名</pre>
</div>
<p>模板在数据目录 Markdown/ 下，改文案直接编辑文件。</p>
</div>"""
        if anchor_cfg not in ttext:
            errors.append("tutorial 查在线模板条目锚点未找到"); tok = False
        # 2) 假人白名单条目改全端口径（整块替换）
        if TUTORIAL_WL_OLD not in ttext:
            errors.append("tutorial 假人白名单条目原文未找到"); tok = False
        # 3) 命令表两行补充
        cmd_online_old = "<tr><td><code>/查在线</code><span class=\"alias\">别名 /在线</span></td><td>查看在线玩家列表与皮肤头像；默认输出图片卡片，想换成文字列表见「查在线输出」设置</td></tr>"
        cmd_online_new = "<tr><td><code>/查在线</code><span class=\"alias\">别名 /在线</span></td><td>查看在线玩家列表与皮肤头像；默认输出图片卡片，想换成文字列表见「查在线输出」设置；开启「查在线假人列表」设置后主列表只显示真实玩家，底部额外列出当前在线的假人</td></tr>"
        if cmd_online_old not in ttext:
            errors.append("tutorial /查在线 命令行原文未找到"); tok = False
        cmd_lb_old = "时长格式与玩家名片统一「X时X分」</td></tr>"
        cmd_lb_new = "时长格式与玩家名片统一「X时X分」；假人插件的假人不再计入（判定口径见「查在线假人列表」设置）</td></tr>"
        if cmd_lb_old not in ttext:
            errors.append("tutorial /在线排行榜 命令行锚点未找到"); tok = False
        if tok and not errors:
            ttext = ttext.replace(anchor_cfg, anchor_cfg + "\n\n" + TUTORIAL_FAKE_CFG, 1)
            ttext = ttext.replace(TUTORIAL_WL_OLD, TUTORIAL_WL_NEW, 1)
            ttext = ttext.replace(cmd_online_old, cmd_online_new, 1)
            ttext = ttext.replace(cmd_lb_old, cmd_lb_new, 1)
            tp.write_text(ttext, encoding="utf-8")
            out = tp.read_text(encoding="utf-8")
            assert "查在线假人列表" in out and "online-list" in out
            assert "1.7.6.1 起全端生效" in out
            print("tutorial.html：命令行 / 白名单口径 / 新 cfg-item 已更新")

    if errors:
        print("\n".join(f"[前置态错误] {e}" for e in errors))
        sys.exit(1)
    print("全部完成")


if __name__ == "__main__":
    main()
