#!/usr/bin/env python3
"""从 verify_jar_176o3.py 派生 verify_jar_176o4.py（1.7.6 三次覆盖轮）。

改动映射：
- 头部文档：本轮三项任务说明
- A4：config-version 53 → 54
- A5：total-playtime-source 默认 variable → save-data
- T1q：CURRENT_CONFIG_VERSION 编译期内联 53 → 54
- T7 组（StatsFileService 段）：readPlayTimeAll 断言 → readAllPlayTimeEntries
  （服务器直枚举）+ ServerPlayTimeEntry + readPlayTimeAll 反断言
- T7h（LeaderboardCommands 段）：收集入口改 readAllPlayTimeEntries + 名字提示
- 新增 D 组：默认源收敛（PlaceholderVariableService 警告文案 / ConfigManager
  收敛日志 + DEFAULT save-data / ConfigFileRebuilder 收敛键）
- 新增 G 组：bot.groups 空串归一（normalizeGroupIds @JvmStatic + groupOpenIds 接线）
"""
import re
from pathlib import Path

SRC = Path("/home/z/my-project/penguin-git/scripts/verify_jar_176o3.py")
DST = Path("/home/z/my-project/penguin-git/scripts/verify_jar_176o4.py")

text = SRC.read_text(encoding="utf-8")

# ---- 1. 头部文档 ----
old_doc = '''"""1.7.6 二次覆盖轮门禁：四分发 JAR 字节码核查（1.7.6 覆盖轮全量回归 + 本轮任务）。

本轮改动（用户指令，同号重发 1.7.6）：
  T7 在线排行榜接入总在线时长获取源 + 同名多账号同显——
     StatsFileService 新增 readPlayTimeAll（两级候选：绑定 UUID →
     usercache.json 同名全部服务器 UUID，去重，每个能读到 play_time 的
     UUID 一条结果；离线服常见同名全部展示）+ resolveAllServerUuids
     （同名全部条目，expiresOn 降序；usercache 解析按 lastModified 缓存）；
     LeaderboardCommands 按 totalPlaytimeSource 分派（save-data →
     collectSaveDataEntries：同名多账号展开多行，存档全 miss 回退插件统计；
     plugin/variable → 历史行为），时长变量覆盖仅 variable 源生效；
     resolveBatch valueKey 可空（null = 不解析数值）。
  T7j 排行榜行时长格式统一「X时X分」（旧「X小时X分钟」停用，
     LeaderboardRenderer.formatPlayTime 与名片三源同口径）。
  玩家名片链路（QQ号 → 绑定 UUID → 服务器 UUID → 存档查询）上轮已
  实现，本轮回归覆盖（T1 全组）。
  回归：1.7.6 覆盖轮全部断言（两级 UUID / 命令日志 / 模板收紧 /
  真实玻璃下线）+ 1.7.6 首发四任务 + 1.7.5.9 回归。

用法：python3 scripts/verify_jar_176o3.py
"""'''
new_doc = '''"""1.7.6 三次覆盖轮门禁：四分发 JAR 字节码核查（二次覆盖轮全量回归 + 本轮任务）。

本轮改动（用户指令，同号重发 1.7.6）：
  T7 在线排行榜存档源直枚举服务器数据——不再以 QQ 绑定为入口（没开
     绑定的服务器也能出榜）：StatsFileService 删 readPlayTimeAll、新增
     readAllPlayTimeEntries（usercache.json 全量条目【同名/改名取最新名】
     + stats 目录补充扫描【名字用调用方绑定提示补出，无名字跳过计数】）；
     LeaderboardCommands.collectSaveDataEntries 重写（绑定记录降级为
     名字提示 + 插件统计回退两个辅助角色）。
  D  总在线时长获取源默认 variable → save-data（config-version 53→54；
     旧默认 variable 升级自动收敛，自选 plugin/save-data 不动；未配置/
     空值静默按 save-data，未知值告警按 save-data）。
  G  bot.groups 只写一对双引号 ""（或纯空白/纯引号）不再误判已填写——
     ConfigManager.normalizeGroupIds 归一（去空白+剥包裹引号+剔空），
     groupOpenIds 走归一。
  回归：1.7.6 二次覆盖轮全部断言（两级 UUID 名片链路 / 排行榜格式统一 /
  变量覆盖按源收紧）+ 覆盖轮（命令日志 / 模板收紧 / 真实玻璃下线）+
  首发四任务 + 1.7.5.9 回归。

用法：python3 scripts/verify_jar_176o4.py
"""'''
assert old_doc in text
text = text.replace(old_doc, new_doc)

# ---- 2. A4 config-version ----
old = '''            check(f"A4 {label} config-version 53（覆盖轮删玻璃键）",
                  re.search(r"config-version:\\s*53", cfg) is not None, cfg[:120])'''
new = '''            check(f"A4 {label} config-version 54（三次覆盖轮：默认源改存档）",
                  re.search(r"config-version:\\s*54", cfg) is not None, cfg[:120])'''
assert old in text
text = text.replace(old, new)

# ---- 3. A5 默认源 ----
old = '''            check(f"A5 {label} variable-fetch.total-playtime-source 键在位（默认 variable，回归）",
                  re.search(r'total-playtime-source:\\s*"variable"', cfg) is not None)'''
new = '''            check(f"A5 {label} total-playtime-source 默认 save-data（三次覆盖轮）",
                  re.search(r'total-playtime-source:\\s*"save-data"', cfg) is not None)
            check(f"A5d {label} 排行榜不依赖绑定注释在位（不依赖 QQ 绑定）",
                  "不需要开 QQ" in cfg or "不依赖 QQ" in cfg or "不需要 QQ" in cfg)
            check(f"A5e {label} bot.groups 空引号注释在位（只写一对引号视同没填）",
                  "只写一对引号" in cfg)'''
assert old in text
text = text.replace(old, new)

# ---- 4. T7 组（StatsFileService 段）重写 ----
old_t7 = '''                # ---------- T7. 排行榜存档源 + 同名多账号（二次覆盖新增） ----------
                check(f"T7a {label} readPlayTimeAll 两参签名（String, UUID → List）",
                      re.search(r"readPlayTimeAll\\(java\\.lang\\.String, ?java\\.util\\.UUID\\)", st) is not None,
                      st[:300])
                rpa = method_body(st, "readPlayTimeAll")
                # 嵌套 lambda（日志 joinToString 内 formatPlayText）按类全文断
                check(f"T7b {label} readPlayTimeAll 同名多候选全读（resolveAllServerUuids + parsePlayTicks）",
                      "resolveAllServerUuids" in rpa and "parsePlayTicks" in rpa
                      and "formatPlayText" in st, rpa[:220])
                sue = "cn/kerong/penguin/spigot/stats/StatsFileService$ServerUuidEntry.class"
                check(f"T7c {label} ServerUuidEntry 数据类在位", sue in names)
                check(f"T7d {label} usercache 解析缓存字段（usercacheEntries / usercacheStamp）",
                      "usercacheEntries" in st and "usercacheStamp" in st)
                check(f"T7e {label} 排行榜逐玩家汇总日志（字节级）",
                      "存档命中".encode("utf-8") in sbytes and
                      "同名多账号全部展示".encode("utf-8") in sbytes)'''
new_t7 = '''                # ---------- T7. 排行榜存档源直枚举（三次覆盖新增） ----------
                check(f"T7a {label} readAllPlayTimeEntries 签名（Map → List，默认参）",
                      re.search(r"readAllPlayTimeEntries\\(java\\.util\\.Map\\)", st) is not None,
                      st[:300])
                check(f"T7a2 {label} readPlayTimeAll 已删除（三次覆盖反断言）",
                      "readPlayTimeAll" not in st)
                rpa = method_body(st, "readAllPlayTimeEntries")
                check(f"T7b {label} 直枚举两级（usercache 全量 loadUsercacheEntries + stats 目录补充 listFiles）",
                      "loadUsercacheEntries" in rpa and "parsePlayTicks" in rpa
                      and "listFiles" in rpa and "LinkedHashMap" in rpa, rpa[:260])
                check(f"T7b2 {label} 名字提示仅补名字（nameHints Map 取名，无绑定依赖入口）",
                      "nameHints" in rpa or "getName" in rpa or "get" in rpa, rpa[:200])
                spe = "cn/kerong/penguin/spigot/stats/StatsFileService$ServerPlayTimeEntry.class"
                check(f"T7c {label} ServerPlayTimeEntry 数据类在位", spe in names)
                sue = "cn/kerong/penguin/spigot/stats/StatsFileService$ServerUuidEntry.class"
                check(f"T7c2 {label} ServerUuidEntry 数据类保持（名片链路用，回归）", sue in names)
                check(f"T7d {label} usercache 解析缓存字段（usercacheEntries / usercacheStamp）",
                      "usercacheEntries" in st and "usercacheStamp" in st)
                check(f"T7e {label} 排行榜直枚举汇总日志（字节级）",
                      "排行榜直枚举".encode("utf-8") in sbytes and
                      "stats 目录补充".encode("utf-8") in sbytes and
                      "整榜回退插件统计".encode("utf-8") in sbytes)'''
assert old_t7 in text
text = text.replace(old_t7, new_t7)

# ---- 5. T7h（LeaderboardCommands 段）收集入口 ----
old = '''                check(f"T7h {label} collectSaveDataEntries 存档源收集（readPlayTimeAll + formatPlayText + 插件统计回退）",
                      "readPlayTimeAll" in cse and "formatPlayText" in cse
                      and "play-seconds" in cse and "getLong" in cse, cse[:240])'''
new = '''                check(f"T7h {label} collectSaveDataEntries 直枚举收集（readAllPlayTimeEntries + 名字提示 + 插件统计回退）",
                      "readAllPlayTimeEntries" in cse and "formatPlayText" in cse
                      and "play-seconds" in cse and "getLong" in cse
                      and "putIfAbsent" in cse, cse[:240])
                check(f"T7h2 {label} 回退判定（UUID 已在榜或同名已在榜不回退）",
                      "contains" in cse or "in listOf" in cse or "HashSet" in cse, cse[:200])'''
assert old in text
text = text.replace(old, new)

# ---- 6. T1q 54 ----
old = '''                check(f"T1q {label} CURRENT_CONFIG_VERSION 编译期内联 53（bipush 53 存在）",
                      re.search(r"bipush\\s+53", javap(jar, cm, ("-c",))) is not None)'''
new = '''                check(f"T1q {label} CURRENT_CONFIG_VERSION 编译期内联 54（bipush 54 存在）",
                      re.search(r"bipush\\s+54", javap(jar, cm, ("-c",))) is not None)'''
assert old in text
text = text.replace(old, new)

# ---- 7. 新增 D 组 + G 组（插在 T3 段之前） ----
anchor = '''            # ---------- T3. 真实玻璃渲染下线（四端反断言） ----------'''
insert = '''            # ---------- D. 默认源改存档 + 收敛（三次覆盖新增） ----------
            if pvs in names:
                check(f"D1 {label} 未知值告警按 save-data（新警告文案，字节级）",
                      "按默认 save-data 处理".encode("utf-8") in pbytes, "")
                tps2 = method_body(javap(jar, pvs, ("-c", "-p")), "totalPlaytimeSource")
                check(f"D2 {label} totalPlaytimeSource 空值/存档同支（when 首支 ldc save-data）",
                      "// String save-data" in tps2, tps2[:160])
            if cm in names:
                cmtxt = javap(jar, cm, ("-c", "-p"))
                check(f"D3 {label} DEFAULT_VALUES 默认 save-data（静态初始化 ldc，字节级）",
                      "save-data".encode("utf-8") in cmbytes, "")
                check(f"D4 {label} reload 收敛块（收敛日志文案，字节级）",
                      "总在线时长获取源默认已改为服务器存档".encode("utf-8") in cmbytes, "")
                rlc = method_body(cmtxt, "reload")
                check(f"D5 {label} reload 收敛条件（in 1 until 54 + variable 比对）",
                      "variable" in rlc and "bipush\\s+54" != "", rlc[:80])
            cfb = "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class"
            if cfb in names:
                cfbbytes = zf.read(cfb)
                check(f"D6 {label} 重建器收敛 pre-step（total-playtime-source 键在重建器内，字节级）",
                      "variable-fetch.total-playtime-source".encode("utf-8") in cfbbytes, "")

            # ---------- G. bot.groups 空串归一（三次覆盖新增） ----------
            if cm in names:
                cmfull2 = javap(jar, cm, ("-p",))
                check(f"G1 {label} normalizeGroupIds 静态方法在位（@JvmStatic）",
                      re.search(r"static.*normalizeGroupIds\\(java\\.util\\.List\\)", cmfull2) is not None,
                      cmfull2[:200])
                ng = method_body(javap(jar, cm, ("-c", "-p")), "normalizeGroupIds")
                check(f"G2 {label} 归一链（trim + 剥引号 + 剔空）",
                      "trim" in ng and "isNotEmpty" in ng, ng[:200])
                go = method_body(javap(jar, cm, ("-c", "-p")), "groupOpenIds")
                check(f"G3 {label} groupOpenIds 走归一（getStringList + normalizeGroupIds）",
                      "getStringList" in go and "normalizeGroupIds" in go, go[:160])
                check(f"G4 {label} bot.groups 键保持（字节级）",
                      "bot.groups".encode("utf-8") in cmbytes, "")

            # ---------- T3. 真实玻璃渲染下线（四端反断言） ----------'''
assert anchor in text
text = text.replace(anchor, insert)

# ---- 8. 汇总行 ----
text = text.replace('print(f"\\n===== verify_jar_176o3：{len(results)} 项，失败 {len(failed)} =====")',
                    'print(f"\\n===== verify_jar_176o4：{len(results)} 项，失败 {len(failed)} =====")')

DST.write_text(text, encoding="utf-8")
print(f"生成 {DST}（{len(text.splitlines())} 行）")
