#!/usr/bin/env python3
"""kb 更新：v1.7.5.1 覆盖版 + fa 版入主线（menu 系统模板强制重置 / 官网 NeoForge
括号手机端折行 / fa 内核对齐 + 绑定去拦截 + /hbkrg qbd + 更新检查 + 移主线）。"""

PATH = "/tmp/AI-KNOWLEDGE.txt"
with open(PATH, encoding="utf-8") as f:
    text = f.read()

# ---- 1. 头部：新「最后更新」行置顶 ----
new_header = (
    "> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-11（**v1.7.5.1 覆盖版 + fa 入主线**：①"
    " **menu.md 强制收敛**——线上用户服务器 /菜单 仍带旧帮助文段，特征匹配升级表覆盖不了历史所有"
    "形态（用户文件无「### 指令菜单」特征时兜底失效）；menu 改**系统 UI 模板**（SYSTEM_TEMPLATE_IDS）"
    "，initialize() 里 resetSystemTemplates **无条件重置为当前默认**（不一致先备份 menu.md.bak，幂等），"
    "自定义走 messages.menu 指向自己的文件名（该路径不释放不重置）；menu 条目从"
    " DEFAULT_CONTENT_UPGRADES 移除（升级表余 4 键）。② **官网 NeoForge 括号版本手机端溢出修复**——"
    ".core 原为 inline-flex+nowrap 整体不可换行，括号标注（MC 1.20.4，1.20.6，1.21.x，Java 21）在"
    " 375px 视口横向溢出；改 flex-wrap:wrap + max-width:100%，标注包 span.core-note（white-space:"
    "normal + overflow-wrap:anywhere），桌面仍单行（chip h=35 实测）、手机自动折到第二行（375px "
    "渲染 0 溢出实测，Playwright）。③ **fa 版（Fabric）1.7.5.1 入主线发版**（用户指令四项）："
    "**内核对齐**——共用 common-Bot 源码（1.7.5.1 全部修复随行）+ config.yml/Markdown 与 NeoForge "
    "完全同步（config-version 43、menu.md、help.md 删）+ KERONGFabric 补 setFullAmountEnabled；"
    "**QQ 绑定去拦截**——enforceLoginBinding 加入后踢出路径删除，JOIN 改 registerPlayerProfile 仅"
    "建档（未绑定玩家正常游玩），/qq skip/unskip 随之移除（rebind/qxqq 保留）；**/hbkrg qbd 取验证码**"
    "——根节点不再整体 requires(2)，qbd 所有玩家可用（已绑定提示无需取码，输出码+有效期+引导 QQ 群"
    "/绑定），管理子命令（reload/info/chs/mem/bpng/bpngsz/qr/help）各自 requires(2)，rebind/qxqq "
    "消息口径改指向 qbd；**更新检查**——UpdateChecker 系 common-Bot 共用源码（initializeRuntime →"
    " scheduleUpdateCheck 8 秒延迟，fa 1.7.4.1 起就带），本轮门禁验证接线（D1-D4）；**移主线**——"
    "settings.gradle.kts 条件 include（server-Fabric/libs/deps.jar + fabric-api-mojmap.jar 就绪时"
    "自动进构建，裸 clone 不破坏双端构建）。**fa 构建管线重建（上轮脚本未入库已丢失）**："
    "scripts/build_fabric_libs.py——fabric-api 7 模块 intermediary→mojang 重映射（关键坑：intermediary"
    " tiny 的「official」列是**混淆名**（vv/ane/a），不是 mojmap 名！需 **MergeMappings.java**（mapping-io"
    " MemoryMappingTree：先读 ProGuard client.txt（mojang→official）再读 intermediary tiny（official→"
    "intermediary）**调序合并**——反序会 IllegalArgumentException: can't merge with disassociated src"
    " namespace）合成三列映射 mojang/official/intermediary，再 tiny-remapper 一次 intermediary→"
    "mojang；tiny-remapper Main CLI：`<input> <output> <mappings.tiny文件(不能传jar)> <from> <to>"
    " [classpath逐个传参] --ignoreconflicts`；scripts/package_jar_fabric.py——模块产物 mojang→"
    "intermediary 反向重映射（同三列映射）+ Spigot 底座合并（剔除同 NF；**不重定位**（Fabric knot"
    " 非 JPMS，fa 1.7.4.1 口径 kotlin/ 原包名）、**不剔除** slf4j/gson）+ snakeyaml 并入。门禁"
    " verify_jar_1751o2 **40 项** + 冒烟 smoke_1751o2 **12 项**（Spigot/fa 双 JAR 同源冒烟各 12/12）"
    "+ **verify_jar_1751_fa 46 项** + 外网 verify_online_o13 **13 项** / **o14 18 项**全绿；git push"
    " main=`a49601f`（覆盖版）→ `bbc69c7`（fa）；三 JAR 已部署 md5 往返一致（Spigot c12cbb1a / "
    "NeoForge 1ede2918 / **Fabric 35654f34**）；fabric 原料本地 /home/z/my-project/fabric-deps/"
    "（服务器 /root/fabric-deps/ 同份）。**官网**：下载区加 Fabric 按钮、Fabric 卡片主线化（去测试版"
    "定位，绑定量变+更新检查入卡片）、历史表 Fabric 1.7.5.1/1.7.4.1 两行、模组版核心行加 Fabric 芯片"
    "（MC 1.20.4 及以上，Java 21）、前置插件表补模组版自动降级说明、更新日志 v1.7.5.1 补覆盖版与 fa"
    " 条目、version.json 同步六条、教程补 fa 绑定差异段）"
)
anchor = "> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-10（**v1.7.5.1**：① 40034128"
assert anchor in text, "头部锚点未找到"
text = text.replace(anchor, new_header + "\n" + anchor, 1)

# ---- 2. §0 版本状态行：fa 停 1.7.4.1 口径更新 ----
old_line = "**fa 版（Fabric）停在 1.7.4.1 未随主线升 1.7.5.1（1.7.5.1 轮起源码已入主线 server-Fabric，只有用户明确要求才更新 fa；官网卡片改「主系已测试版本 / 支持版本」口径，源码包已全面下架官网，本体仍在服务器 downloads/，构建期 fabric libs 在服务器 /root/fabric-deps/ + server-Fabric/README.md）**"
new_line = "**fa 版（Fabric）已于 2026-11-11 升 1.7.5.1 并入主线发版链（用户指令：内核对齐 / QQ 绑定去拦截改为 /hbkrg qbd 取码 / 更新检查随行 / settings.gradle 条件 include 移主线；构建管线 build_fabric_libs.py + package_jar_fabric.py + fabric-tools/MergeMappings.java 已入库，关键坑与三列合并映射见头部；后续 fa 随主线同号发版不再冻结；fabric 原料在服务器 /root/fabric-deps/ 与本地 fabric-deps/，1.20.1 下探评估见 server-Fabric/README.md）**"
assert old_line in text, "§0 fa 行未找到"
text = text.replace(old_line, new_line, 1)

# ---- 3. §12 版本史补条目（置顶插在 1.7.5 条目前；首轮 1.7.5.1 未单列条目并入本条） ----
anchor = "- **1.7.5（2026-11-10，覆盖三轮链，同版本号直接覆盖）**"
entry = (
    "- **1.7.5.1（2026-11-10 首发 + 2026-11-11 覆盖版 + fa 入主线，同版本号直接覆盖）**：首发五项"
    "（40034128 被动回复轮转 RecentGroupMessages 环形 16 / 按钮管理员权限 MemberRoleCache / 模板升级"
    "放宽 UpgradeSpec / 互动回应超时收紧 1.5s+快速重试 / 全量面板刷新）；覆盖版 menu.md 改系统 UI 模板"
    "强制重置（resetSystemTemplates 无条件收敛 + .bak 备份，替代特征匹配——用户服务器历史形态菜单"
    "文段收敛不了，升级表移除 menu 条目余 4 键）；官网 NeoForge 括号版本手机端折行修复（flex-wrap +"
    " core-note，桌面单行不回归）；fa 版（Fabric）1.7.5.1 入主线发版：内核对齐（资源全同步 config 43"
    " + setFullAmountEnabled）、QQ 绑定去拦截（enforceLoginBinding 删、进服仅建档 registerPlayerProfile、"
    "skip/unskip 移除）、/hbkrg qbd 取验证码（/hbkrg 根节点分权：qbd 所有玩家、管理子命令各自"
    " requires(2)）、更新检查随行（UpdateChecker 共用源码）、settings.gradle 条件 include 移主线；"
    "fa 构建管线重建入库（build_fabric_libs / package_jar_fabric / fabric-tools/MergeMappings——"
    "intermediary tiny 的 official 列是混淆名，需三列合并映射 mojang/official/intermediary，"
    "tiny-remapper 的 mappings 必须传解出的 .tiny 文件不能传 jar、classpath 逐个传参）；门禁"
    " verify_jar_1751 43 + 1751o2 40 + 1751_fa 46 + 冒烟 34+12×2 + o12 16 / o13 13 / o14 18 全绿；"
    "md5：首轮 Spigot 6679c015 / NeoForge c4118178，覆盖版 Spigot c12cbb1a / NeoForge 1ede2918 /"
    " Fabric 35654f34。\n"
)
assert anchor in text, "§12 1.7.5 条目未找到"
text = text.replace(anchor, entry + anchor, 1)

with open(PATH, "w", encoding="utf-8") as f:
    f.write(text)
print("kb 三处更新完成")
