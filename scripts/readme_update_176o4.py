#!/usr/bin/env python3
"""README.md 1.7.6 三次覆盖轮更新：94 行摘要段 + 96-104 详史段替换（行切片）。"""
from pathlib import Path

p = Path("/home/z/my-project/penguin-git/README.md")
lines = p.read_text(encoding="utf-8").splitlines(keepends=True)

# 定位锚点
assert lines[93].startswith("> 1.7.6 二次覆盖轮（四项"), f"94 行锚失败: {lines[93][:40]}"
assert lines[95].startswith("### v1.7.6 更新日志（二次覆盖轮"), f"96 行锚失败: {lines[95][:40]}"
assert lines[104].startswith("### v1.7.5.9"), f"105 行锚失败: {lines[104][:40]}"

summary = "> 1.7.6 三次覆盖轮（三项，同号重发；二次覆盖轮四项 / 覆盖轮五项 / 首发四项见 git 历史 730314e / 6f62976 / a440f53）：**在线排行榜存档源直枚举服务器数据**——选「服务器存档数据」源时不再以 QQ 绑定记录为入口（用户口径：没开 QQ 绑定的服务器获取不了，改为直接获取服务器上的 UUID，同名都显示）：usercache.json 全量玩家逐个读 stats/<uuid>.json（同一 UUID 改名多条取 expiresOn 最新名；同名多账号每个 UUID 各一行同显），stats 目录补充扫描 usercache 条目被清理但统计文件还在的老玩家（名字用绑定记录提示补出，无名字跳过计数），绑定记录降级为「名字提示 + 插件统计回退」两个辅助角色（readPlayTimeAll 删除，新增 readAllPlayTimeEntries）。**总在线时长获取源默认改存档**——variable-fetch.total-playtime-source 默认 variable → save-data（config-version 53→54；旧默认升级自动收敛，自选 plugin/save-data 不动；未配置/空值静默按存档，未知值告警按存档；名片读不到回退链不变）。**bot.groups 空引号修复**——群列表只写一对双引号 \"\"（或纯空白/纯引号）不再误判已填写（旧版 getStringList 返回 [\"\"] 非空 → 自动绑定不触发、群白名单恒不匹配），normalizeGroupIds 归一（去空白+剥包裹引号+剔空）视同未填写。门禁 verify_jar_176o4 381/381 全绿（二次覆盖+覆盖+首发全量回归 + 三次覆盖 T7/D/G 组）；冒烟 smoke176o4 30/30（直枚举端到端：同名两账号/改名取新名/孤儿补名/零绑定出榜/无 stats 空表 + 群 ID 归一五断言 + 默认源）。\n"

detail = """### v1.7.6 更新日志（三次覆盖轮：排行榜存档源直枚举 + 默认源改存档 + 群列表空引号修复；二次覆盖轮见 git 历史 730314e）

- **任务1（排行榜存档源直枚举，用户口径「在线排行榜如果信息源是服务器存档，不要先走QQ绑定uuid，不然那些没有开QQ绑定的服务器就获取不了了，改为直接获取服务器上的uuid（有同名的都显示）」）**：`StatsFileService.readAllPlayTimeEntries(nameHints)` 取代 readPlayTimeAll——主入口 usercache.json **全量条目**（服务器口径每个登录过的玩家）逐个读 stats/<uuid>.json 的 play_time：同一 UUID 因改名存在新旧两条记录时取 expiresOn 最新的名字；同名不同 UUID（改名前的旧账号 / 大小写变体 / 多个同名玩家）每个 UUID 各一条同时展示。补充扫描 stats 目录：usercache 条目过期被服务器清理但统计文件还在的老玩家（长期未登录，恰是总时长榜常客）用调用方传入的绑定记录提示（playerUuid → playerName）补出名字；无任何名字来源的统计文件跳过并计数（展示需要名字）。`LeaderboardCommands.collectSaveDataEntries` 重写——绑定记录不再是数据入口，只做两个辅助角色：①名字提示 ②绑定玩家在存档里一行都没有（自己 UUID 无统计文件且同名无命中）时回退插件统计秒数；没开 QQ 绑定的服务器照常出榜。
- **任务2（默认源改存档，用户口径「总在线时长信息源默认改为服务器存档」）**：`variable-fetch.total-playtime-source` 默认 variable → save-data（ConfigManager DEFAULT_VALUES + 三端 config.yml 模板 + PlaceholderVariableService 未配置/空值默认分支；config-version 53 → 54）。旧默认收敛双保险：ConfigFileRebuilder 重建 pre-step 与 reload 兜底路径同款判定（磁盘值 == 旧默认 variable 时自动改写 save-data 并写控制台提示；自行选过 plugin / save-data 的不动）。玩家名片回退链不变（存档 → 变量 → 插件统计）。
- **任务3（群列表空引号修复，用户口径「如果群open ID只有一个\"\"双引号也会判定已经填写」）**：`ConfigManager.normalizeGroupIds` 归一（每项去首尾空白 → 剥一层包裹双引号 → 再去空白，空结果剔除），`groupOpenIds()` 走归一——bot.groups 只写 `- \"\"`（照注释示例占位没填真 ID 的常见写法）不再被 getStringList 的 [\"\"] 非空列表误判为已配置：自动绑定恢复触发、群白名单匹配回归正常、与 `groups: []` 同语义；残留包裹引号的真 ID（复制粘贴带入）顺手剥除。config.yml 注释补「只写一对引号（如 [\"\"]）视同没填」。
- **验证**：门禁 verify_jar_176o4 **381/381 全绿**（1.7.6 二次覆盖轮 + 覆盖轮 + 首发四任务全量回归 + 本轮 T7 组：readAllPlayTimeEntries 泛型全形态签名 / readPlayTimeAll 反断言 / 直枚举两级（loadUsercacheEntries + listFiles）/ ServerPlayTimeEntry / 排行榜直枚举日志字节级 / collectSaveDataEntries 直枚举收集；D 组：默认源警告文案 / DEFAULT_VALUES save-data / reload 收敛（ldc variable + bipush 54）/ 重建器收敛键；G 组：normalizeGroupIds @JvmStatic 泛型签名 / Companion 真身归一链（StringsKt.trim + CharSequence.length，map/filter 内联坑）/ groupOpenIds 接线）。冒烟 smoke176o4 **30/30**（H 组直枚举端到端：同名两账号同显 / 同 UUID 改名取新名 / 孤儿统计文件提示补名 / 无提示孤儿跳过 / ghost 不计 / 零绑定场景照常出榜 / 无 stats 目录空表；N 组归一五断言；S 组默认源=save-data + 三源常量回归；A/B/C/F/G/I/J 名片与格式回归）。首轮门禁 12 红=断言三坑（泛型裸类型两处 / @JvmStatic 外类委托方法真身在 Companion / isNotEmpty 内联为 length）按真实字节码形态改写；冒烟 S 组首轮在 Spigot 包上 NoClassDefFoundError（反射方法表需要 org.bukkit.entity.Player）改用自带 bukkit 兼容层的 NF 分发 JAR 作 classpath。构建坑合规：rm -rf 四模块 build 全量重编（common-Bot 中途改动后再全量重编一轮）+ Fabric remap 缓存删除重打 + 同号 base=1.7.5.9。

"""

new_lines = lines[:93] + [summary, "\n"] + [detail] + lines[104:]
p.write_text("".join(new_lines), encoding="utf-8")
print("README 更新完成")
text = p.read_text(encoding="utf-8")
assert "三次覆盖轮" in text and "verify_jar_176o4 381/381" in text
assert "### v1.7.6 更新日志（三次覆盖轮" in text
assert "### v1.7.5.9 更新日志" in text
assert text.count("### v1.7.6") == 1
assert text.count("> 1.7.6 三次覆盖轮") == 1
print("锚点复核通过")
