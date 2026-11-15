#!/usr/bin/env python3
"""README.md 1.7.6.1 更新：当前版本行 + 摘要段 + 详史段插入（行切片，v1.7.6 及更早保留）。"""
from pathlib import Path

p = Path("/home/z/my-project/penguin-git/README.md")
lines = p.read_text(encoding="utf-8").splitlines(keepends=True)

# 定位锚点（0 基）
idx_version = None
idx_summary = None
idx_detail = None
for i, line in enumerate(lines):
    if line.startswith("当前版本：**1.7.6**"):
        idx_version = i
    if line.startswith("> 1.7.6 三次覆盖轮"):
        idx_summary = i
    if line.startswith("### v1.7.6 更新日志（三次覆盖轮"):
        idx_detail = i
assert idx_version is not None, "当前版本行锚失败"
assert idx_summary is not None and idx_detail is not None, "摘要/详史锚失败"

lines[idx_version] = lines[idx_version].replace("当前版本：**1.7.6**", "当前版本：**1.7.6.1**")

summary = "> 1.7.6.1（两项；1.7.6 三次覆盖轮及更早见下方详史与 git 历史）：**在线排行榜不再计入假人插件假人时长**——新增 FakePlayerRegistry 统一口径（登录连接为空 / 回环 / 内存连接，或 qq-bind.fake-player-whitelist 名单命中），三端 join 事件登记 fake-players.json（离线假人的历史时长同样排除；真人以真实外网连接进服自动自愈恢复上榜），存档直枚举与插件统计两个收集源都过滤、过滤计数写日志；白名单语义全端化（Fabric 未绑定豁免照旧，所有端都用它识别假人）。**新配置 online-list.show-fake-players（默认关）**——开启后 /查在线 主列表只显示真实玩家，末页底部额外画「假人在线」小节（当前在线的假人，多页时只画末页）；文字列表（markdown）同开关同口径（人数只算真实玩家 + 末尾假人摘要行）；关闭时与旧版一致。门禁 verify_jar_1761 535/535 全绿（三次覆盖轮全量回归 + FK 组假人断言）；冒烟 smoke1761 31/31（R 组登记表端到端 18 断言 + S 组新键读取 + O 组假人小节渲染含像素验证）。\n"

detail = """### v1.7.6.1 更新日志（假人排行榜过滤 + 查在线假人列表；1.7.6 三次覆盖轮见下方）

- **任务1（排行榜假人过滤，用户口径「修复在线排行榜假人插件的假人时长也在排行榜里面的问题」）**：新增共享服务 `FakePlayerRegistry`——判定口径与 1.7.5.8 绑定拦截豁免同源（登录连接为空【进程内注入】/ 回环 127.x.x.x【FakeChannel】/ 内存连接，或 qq-bind.fake-player-whitelist 名单命中【大小写不敏感】）。三端 join 事件评估登记：命中 → 写入插件数据目录 `fake-players.json`（UUID + 玩家名，原子写；下线假人的历史存档时长同样被排行榜排除）；不命中（真实外网连接）→ 自愈移除历史误登记（本机 127.0.0.1 测试等原因）。`LeaderboardCommands` 快照时对在线玩家实时判定（liveIsFake：名单或平台注入的 connectionDetector），收集后三路过滤（在线假人 UUID / 持久登记 UUID / 名单名）——存档直枚举与插件统计两个源都过滤，全部被排除时回复无数据；过滤计数与名单写日志。三端 connectionDetector 注册：Spigot onEnable（player.address 判定）/ NeoForge onServerStarted（BindLoginGate.isLocalFakeConnection）/ Fabric registerAll（FakePlayerExempt.isLocalFakePlayer 转 public，intermediary 反射两版通吃）。
- **任务2（查在线假人列表，用户口径「给配置文件添加一个配置，当这个配置打开的时候（默认关）查在线将额外显示一个假人列表，里面显示的是当前在线的假人」）**：新配置 `online-list.show-fake-players`（默认关，reload 即时生效；config-version 54 → 55）。开启后：图片版主列表与「在线 N」人数只算真实玩家，末页底部额外画「假人在线」小节（独立小标题 + 「N 假人在线」胶囊 + 同款 3 列头像网格；多页时只在末页；假人拿不到皮肤画名字占位块）；文字版（motd.use-markdown）同开关同口径——人数与名单只算真实玩家，末尾追加「假人在线（N）：xxx、xxx」摘要行。关闭时全部混排与旧版完全一致（缓存 key 含开关与两段名单，切换自然失效）。
- **验证**：门禁 verify_jar_1761 **535/535 全绿**（=176o4 全量回归 + FK 组：FakePlayerRegistry 四方法签名 / 登记文件与白名单键字节级 / 排行榜 isExcluded 接线与假人过滤日志 / OnlineSnapshot+PlayerInfo 快照类 / OnlineListData.fakeEntries 内部类字段与双 List 构造形态 / drawFakeListHeader 与三参 measurePageHeight / RenderSettings 新键读取 / markdown 分流 isFakeName / 三端 connectionDetector setConnectionDetector 注册与 join 接线 / Fabric isLocalFakePlayer 转公开；首轮 24 红=断言六坑——Kotlin lambda 合成方法体抽不到改类全文 / 命名参数不出现在字节码按构造器形态断 / 内部类字段在外类查不到 / const val 字段声明存在误设反断言 / 属性赋值编译为 setConnectionDetector / renderSingle 的 FAKE_SECTION_GAP 反断言误伤）。冒烟 smoke1761 **31/31**（R 组登记表端到端：落盘→持久排除→大小写→重载往返→自愈移除→白名单命中→liveIsFake 名单/检测器/置空回退→在线集合→白名单并入；S 组新键 true/缺失默认 false/配置整树替换生效；O 组渲染：高度公式 172 差值 / 1350×预期高度 / 假人小节标题像素在位 + 无假人反例 / 空主列表 + 假人 / 61 人 + 2 假人分两页小节只在末页；假桩 = 兼容层 JavaPlugin + KERONG 全接口实现，BotShared 真实注入）。字体子集核对：假人在线等新文案全部字形在位（坑表缺字坑）。视觉复核：主列表 5 真人 + 假人小节 3 假人渲染无重叠错位。构建坑合规：rm -rf 四模块 build 全量重编 + Fabric remap 缓存删除重打 + base=1.7.6。

"""

new_lines = lines[:idx_summary] + [summary, "\n"] + [detail] + lines[idx_summary:]
p.write_text("".join(new_lines), encoding="utf-8")
print("README 更新完成")
text = p.read_text(encoding="utf-8")
assert "当前版本：**1.7.6.1**" in text
assert "### v1.7.6.1 更新日志" in text
assert "### v1.7.6 更新日志（三次覆盖轮" in text
assert "### v1.7.5.9 更新日志" in text
assert text.count("### v1.7.6.1") == 1
assert "verify_jar_1761 535/535" in text
print("锚点复核通过")
