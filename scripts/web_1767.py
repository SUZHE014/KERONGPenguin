#!/usr/bin/env python3
"""官网三件 + README 更新（1.7.6.7：签到随机奖励 + /hbkrg qifx 群发）。

锚点精确替换（多行锚点唯一性断言，count==1 保险）：
  version.json  — version 字段 + changelog 整段重写（两条）
  index.html    — 主下载框（版本/日期/四链接）、历史表置顶四行、更新日志置顶块
  tutorial.html — /hbkrg 命令表 qifx 行 + checkin.reward 随机写法说明
  README.md     — 当前版本行 + 版本总结行 + 更新日志节置顶
"""
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(REPO, "website")
TODAY = "2026-11-22"
VER = "1.7.6.7"

fails = []


def patch(path, pairs):
    full = os.path.join(REPO, path)
    data = open(full, encoding="utf-8").read()
    for old, new in pairs:
        cnt = data.count(old)
        if cnt != 1:
            fails.append(f"{path}: 锚点计数 {cnt}（应为 1）: {old[:60]!r}...")
            continue
        data = data.replace(old, new)
    open(full, "w", encoding="utf-8").write(data)
    print(f"  {path}: {len(pairs)} 处替换")


# ---------- version.json ----------
vj_path = os.path.join(WEB, "version.json")
vj = json.load(open(vj_path, encoding="utf-8"))
vj["version"] = VER
vj["changelog"] = [
    "① 签到金币奖励支持随机区间：qq-bind.checkin.reward 两种写法——固定数额照旧填数字（如 100.0，行为不变）；随机区间填「最小~最大」（如 0~100，每次签到在区间内随机发放，含两端，两端都是整数时抽整数）；签到成功回复带「随机 0~100」标注让群友看得出是抽的；写错的值自动回退默认 100.0 并记文件日志；Spigot / NeoForge / Fabric 三端一致",
    "② 新增管理员群发指令 /hbkrg qifx <内容>：让机器人把这条消息发送到配置的全部 QQ 群（bot.groups），发公告 / 通知一键到位；游戏内与服务器控制台均可执行，内容支持空格（上限 1200 字）；发送异步进行，完成后反馈成功 / 失败群数（失败原因见 logs/qq 日志）；未连接机器人或未配置群时直接提示不发",
]
json.dump(vj, open(vj_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("  website/version.json: 版本 + changelog 两条")

# ---------- index.html ----------
idx_pairs = [
    # 主下载框：版本 + 日期
    (">版本 1.7.6.5</p>", ">版本 1.7.6.7</p>"),
    (">发布于 2026-11-18</p>", f">发布于 {TODAY}</p>"),
    # 主下载框四链接
    ("https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.6.5.jar\" class=\"dl-btn\"",
     "https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.6.7.jar\" class=\"dl-btn\""),
    ("https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.6.5.jar\" class=\"dl-btn\"",
     "https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.6.7.jar\" class=\"dl-btn\""),
    ("https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.6.5.jar\" class=\"dl-btn\"",
     "https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.6.7.jar\" class=\"dl-btn\""),
    ("https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.6.5.jar\" class=\"dl-btn\"",
     "https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.6.7.jar\" class=\"dl-btn\""),
]
# 历史表置顶四行
idx_old_row = "<tr><td>NeoForge 1.7.6.5</td><td>2026-11-18</td>"
idx_new_rows = (
    "<tr><td>NeoForge 1.7.6.7</td><td>2026-11-22</td><td><a href=\"https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.6.7.jar\">下载</a></td></tr>\n"
    "<tr><td>Spigot 1.7.6.7</td><td>2026-11-22</td><td><a href=\"https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.6.7.jar\">下载</a></td></tr>\n"
    "<tr><td>Fabric 1.7.6.7（1.20.4 通用包）</td><td>2026-11-22</td><td><a href=\"https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.6.7.jar\">下载</a></td></tr>\n"
    "<tr><td>Fabric 1.7.6.7（1.20.1 独立包）</td><td>2026-11-22</td><td><a href=\"https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.6.7.jar\">下载</a></td></tr>\n"
    "<tr><td>NeoForge 1.7.6.5</td><td>2026-11-18</td>"
)
idx_pairs.append((idx_old_row, idx_new_rows))
# 更新日志置顶块
idx_changelog_anchor = "<div class=\"changelog\">\n<div class=\"version\"><h3>v1.7.6.5</h3>"
idx_changelog_new = (
    "<div class=\"changelog\">\n"
    "<div class=\"version\"><h3>v1.7.6.7</h3><span class=\"date\">2026-11-22</span><ul>\n"
    "<li>① 签到金币奖励支持随机区间：qq-bind.checkin.reward 两种写法——固定数额照旧填数字（如 100.0，行为不变）；随机区间填「最小~最大」（如 0~100，每次签到在区间内随机发放，含两端，两端都是整数时抽整数）；签到成功回复带「随机 0~100」标注让群友看得出是抽的；写错的值自动回退默认 100.0 并记文件日志；Spigot / NeoForge / Fabric 三端一致</li>\n"
    "<li>② 新增管理员群发指令 /hbkrg qifx &lt;内容&gt;：让机器人把这条消息发送到配置的全部 QQ 群（bot.groups），发公告 / 通知一键到位；游戏内与服务器控制台均可执行，内容支持空格（上限 1200 字）；发送异步进行，完成后反馈成功 / 失败群数（失败原因见 logs/qq 日志）；未连接机器人或未配置群时直接提示不发</li>\n"
    "</ul></div>\n\n<div class=\"version\"><h3>v1.7.6.5</h3>"
)
idx_pairs.append((idx_changelog_anchor, idx_changelog_new))
patch("website/index.html", idx_pairs)

# ---------- tutorial.html ----------
tut_pairs = [
    # /hbkrg 命令表加 qifx 行（锚在 qr 行之后——qr 行是命令表行）
    ("<tr><td><code>/hbkrg qr</code></td><td>发起 QQ 机器人扫码登录（控制台二维码 + 扫码链接，扫码后凭据自动写入并连接；每次执行都会取消旧会话并输出全新二维码，二维码过期自动刷新重发、每次会话上限 3 次，超过后重新执行本命令即可）；凭据已配置时同样可用，扫码成功后替换现有凭据并以新凭据重连</td></tr>",
     "<tr><td><code>/hbkrg qr</code></td><td>发起 QQ 机器人扫码登录（控制台二维码 + 扫码链接，扫码后凭据自动写入并连接；每次执行都会取消旧会话并输出全新二维码，二维码过期自动刷新重发、每次会话上限 3 次，超过后重新执行本命令即可）；凭据已配置时同样可用，扫码成功后替换现有凭据并以新凭据重连</td></tr>\n"
     "<tr><td><code>/hbkrg qifx &lt;内容&gt;</code><span class=\"alias\">管理员</span></td><td>群发消息——让机器人把这条消息发送到配置的全部 QQ 群（bot.groups）；游戏内与服务器控制台均可执行，内容支持空格（上限 1200 字）；发送异步进行，完成后反馈成功 / 失败群数；未连接机器人或未配置群时直接提示不发（1.7.6.7 新增）</td></tr>"),
    # checkin.reward 随机写法
    ("<h4>金币奖励 <span class=\"key\">checkin.reward</span></h4>\n<div class=\"config\">\n<pre>qq-bind:\n  checkin:\n    reward: 100.0  # 每次签到奖励的金币数</pre>\n</div>\n<p>经 Vault 发放；未装 Vault 但装了 CoinsEngine 时改走 CoinsEngine。发放后按「前后余额差」校验到账，没到账会在回复里提示，不会悄悄吞掉。</p>",
     "<h4>金币奖励 <span class=\"key\">checkin.reward</span></h4>\n<div class=\"config\">\n<pre>qq-bind:\n  checkin:\n    reward: 100.0  # 固定：每次签到发 100\n    # reward: 0~100 # 随机：每次在 0~100 内随机发放</pre>\n</div>\n<p>两种写法：固定数额填数字（如 100.0）；随机区间填「最小~最大」（如 0~100，每次签到在区间内随机发放，含两端，两端都是整数时抽整数——签到回复会带「随机 0~100」标注）。写错的值自动回退默认 100.0 并记文件日志。经 Vault 发放；未装 Vault 但装了 CoinsEngine 时改走 CoinsEngine。发放后按「前后余额差」校验到账，没到账会在回复里提示，不会悄悄吞掉（1.7.6.7 起支持随机写法）。</p>"),
]
patch("website/tutorial.html", tut_pairs)

# ---------- README.md ----------
readme_summary = (
    "> 1.7.6.7：**签到金币奖励随机区间 + 管理员群发指令 /hbkrg qifx**"
    "（用户口径「添加签到获得的经济可以设置成随机的（如：0~100）」"
    "「添加指令：/hbkrg qifx <内容> 让机器人在填写的群中都发送这条消息（管理员）」）——"
    "①新类 CheckinRewardSpec（sealed：Fixed/Range）：qq-bind.checkin.reward 双写法解析——"
    "固定数额（数字 / 纯数字字符串，与旧版行为完全一致）或随机区间字符串「最小~最大」"
    "（0~100 每次签到随机，含两端；两端都是整数时整数随机，任一端小数则连续随机收敛两位；"
    "倒序自动排序、负下限夹 0、多段 / 空段 / 非数字解析失败回退默认 100.0 + 文件日志）；"
    "CheckInCommands 判定与发放全走 spec（maxAmount>0 判启用、roll() 出本次数额、"
    "随机模式回复带「（随机 0~100）」标注）；QqBindManager.checkinRewardSpec getter + "
    "checkinReward 兼容口径（随机返回中点）；KERONGPenguinAPI 新增 getCheckinRewardSpec/"
    "getCheckinRewardText。②qifx：QClient.broadcastAdminNotice（逐群同步发送统计成败，"
    "集群路由让位规则与聊天转发一致）+ 三端命令层（Spigot when 分支 + Tab 补全；"
    "NeoForge/Fabric brigadier literal qifx requires(2) + greedyString 参数）——"
    "游戏内与控制台均可执行，内容上限 1200 字，未连接 / 未配置群 / 超长直接提示不发。"
    "config-version 63→64（reward 双写法注释刷新，无新键）。"
    "冒烟 Test1767 42/42；门禁 verify_jar_1767 全绿\n"
    ">\n"
)
readme_log = (
    "### 1.7.6.7 更新日志（签到金币奖励随机区间 + 管理员群发指令 /hbkrg qifx）\n\n"
    "- **任务1（签到金币奖励随机区间，用户口径「添加签到获得的经济可以设置成随机的（如：0~100）」）**："
    "新类 `CheckinRewardSpec`（common-Bot qqbind 包，sealed class：`Fixed(amount)` / `Range(min, max)`，"
    "`maxAmount` 启用判定 + `roll()` 抽取 + `displayText()` 配置文本）——`qq-bind.checkin.reward` 双写法："
    "固定数额（YAML 数字 / 纯数字字符串，行为与旧版完全一致，已配置数字的服务器零变化）"
    "或随机区间字符串「最小~最大」（如 `0~100`，每次签到在区间内随机，含两端；两端都是整数时按整数随机"
    "——0~100 抽出 0、1、2…100 不出现小数；任一端是小数则连续随机收敛到两位）；倒序写法自动排序"
    "（150~50→50~150）、负下限夹 0、多段 / 空段 / 非数字解析失败回退默认 100.0 并记文件日志"
    "（跟随旧版 getDouble default 语义）。`CheckInCommands` 金币判定与发放全走 spec："
    "`rewardSpec.maxAmount > 0` 判启用（0~100 视为启用、`reward: 0` / `0~0` 视为关闭）、"
    "`roll()` 抽出本次数额（在线入账 / 离线记账 / 到账校验同链路）、随机模式回复带「（随机 0~100）」标注"
    "（固定模式输出与旧版一字不差）；`QqBindManager.checkinRewardSpec` getter 实时读取 reload 即生效，"
    "`checkinReward` 兼容口径保留（随机模式返回区间中点）；`KERONGPenguinAPI` 新增 "
    "`getCheckinRewardSpec()` / `getCheckinRewardText()`。三端 config.yml reward 注释补双写法说明；"
    "config-version 63→64（无新键纯注释刷新 + 解析升级）。\n"
    "- **任务2（管理员群发指令，用户口径「添加指令：/hbkrg qifx <内容> 让机器人在填写的群中都发送这条消息（管理员）」）**："
    "`QClient.broadcastAdminNotice(content)`——向全部配置群逐群同步发送（与聊天转发同一条集群路由让位规则），"
    "逐群统计成败返回 (ok, fail)，失败原因记 logs/qq 日志；三端命令层：Spigot `KERONGCommand` when 分支 "
    "`qifx` + `SUBCOMMANDS` Tab 补全 + 帮助行；NeoForge / Fabric brigadier literal(qifx) requires 权限 2 + "
    "greedyString 参数（内容支持空格，控制台与游戏内均可执行）。内容上限 1200 字（超长直接拒绝并提示）；"
    "发送在异步线程进行，完成后回主线程 / 服务器线程反馈「群发完成：成功 N 个群，失败 M 个群」；"
    "未连接机器人 / 未配置群 / 内容为空均直接提示不发。plugin.yml usage 行同步。\n"
    "- **验证与发布**：冒烟 `Test1767` 42/42（CR 组 CheckinRewardSpec 解析 / maxAmount / roll 整数与小数区间 / "
    "displayText / parse 回退 + QN 组 QClient 未连接安全语义 + CB 组 CheckInCommands 随机标注常量——"
    "**坑：Fabric remap 缓存复用不校验模块 jar 新旧，新类没进分发 JAR，删 deps-work/fabric-libs/package/ "
    "缓存重打后才进（坑表 343 再现）+ fabric-deps 素材缺 mapping-io 从 git deps-work 补**）；"
    "门禁 `verify_jar_1767` 全绿（A 组新类四条目四端在位 + @JvmStatic javap 断言；B 组签到接线随机标注 / "
    "roll / getMaxAmount invokevirtual / API 新方法；C 组 qifx 三端命令类常量 + plugin.yml + QClient 方法四端；"
    "D 组 cv64 + 双写法注释 + 版本六处；V 组官网；R 组回归——**坑：javap 类名不能带 .class 后缀否则静默空输出；"
    "parse 方法体的字符串常量在 $Companion 字节码里不在外类**）。官网三件 + README 本文件。\n\n"
)
readme_pairs = [
    ("当前版本：**1.7.6.5**", "当前版本：**1.7.6.7**"),
    ("> 1.7.6.5：**游戏内 AI 唤醒兼容格式化聊天插件", readme_summary + "> 1.7.6.5：**游戏内 AI 唤醒兼容格式化聊天插件"),
    ("### 1.7.6.5 更新日志（游戏内 AI 唤醒兼容格式化聊天 + 表情兜底文案可配置 + 爬取安全机制 + 爬取默认开）",
     readme_log + "### 1.7.6.5 更新日志（游戏内 AI 唤醒兼容格式化聊天 + 表情兜底文案可配置 + 爬取安全机制 + 爬取默认开）"),
]
patch("README.md", readme_pairs)

if fails:
    print("失败:")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("官网三件 + README 更新完成")
