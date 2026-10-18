#!/usr/bin/env python3
"""从 verify_jar_1674.py 派生 verify_jar_1675.py（1.6.7.5 门禁）。

改动：
1. DIST / JAR 路径 → 1.6.7.5 发布目录；
2. V 组版本断言 → 1.6.7.5（含无 1.6.7.4 残留）；
3. S30 README 断言 → v1.6.7.5 更新日志；
4. 新增 SB / GA / PRS 三组断言（1.6.7.5 三项修复）。

用法：python3 scripts/make_verify_1675.py
"""
import re

SRC = "scripts/verify_jar_1674.py"
DST = "scripts/verify_jar_1675.py"

with open(SRC, encoding="utf-8") as f:
    text = f.read()

# 1. 路径与版本
text = text.replace(
    'DIST = "/home/z/my-project/download/kerongpenguin-1.6.7.4-release"',
    'DIST = "/home/z/my-project/download/kerongpenguin-1.6.7.5-release"',
)
text = text.replace("KERONGPenguin_Spigot-1.6.7.4.jar", "KERONGPenguin_Spigot-1.6.7.5.jar")
text = text.replace("KERONGPenguin_NeoForge-1.21.1-1.6.7.4.jar", "KERONGPenguin_NeoForge-1.21.1-1.6.7.5.jar")

# 2. 头部说明
text = text.replace(
    "1.6.7.4 门禁：双端分发 JAR 内容断言。",
    "1.6.7.5 门禁：双端分发 JAR 内容断言（1.6.7.4 全部断言保持 + 三项修复新断言）。\n\n"
    "1.6.7.5 新增：\n"
    "  SB. SparkBridge 反射链修复（方法一律从公共 API 接口解析——实现类是包私有，\n"
    "      旧版对实现类取 Method 抛 IllegalAccessException 全指标被吞）\n"
    "  GA. AI 聊天艾特判定（GROUP_AT 帧级捕获 + msg_id 去重 + 行首占位剥离）\n"
    "  PRS. 查性能卡片实色不透明面板（无任何 alpha 混合）",
)

# 3. V 组版本断言
text = text.replace(
    'check("V1 plugin.yml version 1.6.7.4", "version: \'1.6.7.4\'" in read_text(zf, "plugin.yml"))',
    'check("V1 plugin.yml version 1.6.7.5", "version: \'1.6.7.5\'" in read_text(zf, "plugin.yml"))',
)
text = text.replace(
    'check("V2 plugin.yml 无 1.6.7.3 残留（1.6.7.4 覆盖）", "version: \'1.6.7.3\'" not in read_text(zf, "plugin.yml"))',
    'check("V2 plugin.yml 无 1.6.7.4 残留（1.6.7.5 覆盖）", "version: \'1.6.7.4\'" not in read_text(zf, "plugin.yml"))',
)
text = text.replace(
    'check("V3 mods.toml version 1.6.7.4", \'version = "1.6.7.4"\' in toml)',
    'check("V3 mods.toml version 1.6.7.5", \'version = "1.6.7.5"\' in toml)',
)
text = text.replace(
    'check("V4 mods.toml 无 1.6.7.3 残留（1.6.7.4 覆盖）", \'version = "1.6.7.3"\' not in toml)',
    'check("V4 mods.toml 无 1.6.7.4 残留（1.6.7.5 覆盖）", \'version = "1.6.7.4"\' not in toml)',
)

# 4. README 断言
text = text.replace('"### v1.6.7.4 更新日志" in src("README.md")', '"### v1.6.7.5 更新日志" in src("README.md")')

with open(DST, "w", encoding="utf-8") as f:
    f.write(text)

print(f"已生成 {DST}（{len(text.splitlines())} 行）——还需追加 SB/GA/PRS 断言组")
