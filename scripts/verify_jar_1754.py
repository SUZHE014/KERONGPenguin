#!/usr/bin/env python3
"""verify_jar_1754：1.7.5.4 门禁（按钮管理员判定收敛 + 模式 actionType + 全局按钮门禁 + 菜单排版改版）。

三 JAR（Spigot / NeoForge / Fabric）字节码级断言。
- A 产物与版本（1.7.5.4 三端）
- B 任务 A：按钮验身标记 krg_btn_admin（CommandSupport 常量 + isAdmin 短路 + dispatchAsClicker 双写 + AI 管理三按钮门禁构造）
- C 任务 B：MANUAL 模式管理按钮 actionType=2 分支（buttonFor 构造逻辑 + 常量在位）
- D 任务 C：全局按钮门禁（GLOBAL_MEMBER + 双形态构造 + onInteraction userOpenId + switchServer 带参 + 双路由调用同步）
- E 任务 D：菜单排版改版（layoutRows + SHORT_LABEL_EXCEPTIONS「AI管理」特判 + 每页 4 行 + menu.md 新默认 + 收敛表）
- F 回归：1.7.5.2/1.7.5.3 门禁与面板联动随行
"""
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
SPIGOT = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.4.jar"
NEOFORGE = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.4.jar"
FABRIC = REPO / "build" / "dist" / "KERONGPenguin_Fabric-1.20.4-1.7.5.4.jar"

JARS = [("Spigot", SPIGOT), ("NeoForge", NEOFORGE), ("Fabric", FABRIC)]

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if not ok and detail else ""))


def class_bytes(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name)


def has_str(jar, cls, s):
    try:
        return s.encode() in class_bytes(jar, f"{cls}.class")
    except KeyError:
        return False


def res_bytes(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name)


def entry_exists(jar, name):
    with zipfile.ZipFile(jar) as z:
        return name in z.namelist()


CS = "cn/kerong/bot/events/commands/CommandSupport"
MB = "cn/kerong/bot/events/MenuButtons"
PC = "cn/kerong/bot/events/commands/PublicCommands"
SSB = "cn/kerong/bot/events/ServerSelectButtons"
IAL = "cn/kerong/bot/events/InterActionListener"
IFP = "cn/kerong/bot/events/InteractionFastPath"
MT = "cn/kerong/penguin/spigot/qqbind/MessageTemplates"


def main():
    print("== A. 产物与版本 ==")
    check("A1 三 JAR 存在", all(j.is_file() for _, j in JARS),
          "; ".join(str(j) for _, j in JARS if not j.is_file()))
    if not SPIGOT.is_file():
        return finish()
    plugin_yml = res_bytes(SPIGOT, "plugin.yml").decode()
    check("A2 Spigot plugin.yml 版本 1.7.5.4", "version: '1.7.5.4'" in plugin_yml)
    mods_toml = res_bytes(NEOFORGE, "META-INF/mods.toml").decode()
    nf_toml = res_bytes(NEOFORGE, "META-INF/neoforge.mods.toml").decode()
    check("A3 NeoForge mods.toml 双声明版本 1.7.5.4",
          'version = "1.7.5.4"' in mods_toml and 'version = "1.7.5.4"' in nf_toml)
    check("A4 NeoForge MOD_VERSION 1.7.5.4",
          has_str(NEOFORGE, "cn/kerong/penguin/neoforge/KERONGNeoForge", "1.7.5.4"))
    fabric_mod = res_bytes(FABRIC, "fabric.mod.json").decode()
    check("A5 Fabric fabric.mod.json 版本 1.7.5.4", '"version": "1.7.5.4"' in fabric_mod)

    print("== B. 任务 A：按钮验身标记 krg_btn_admin ==")
    for tag, jar in JARS:
        check(f"B1 {tag} CommandSupport 常量 krg_btn_admin", has_str(jar, CS, "krg_btn_admin"))
        check(f"B2 {tag} CommandSupport isButtonVerifiedAdmin 方法在位",
              has_str(jar, CS, "isButtonVerifiedAdmin"))
        check(f"B3 {tag} dispatchAsClicker 写验身标记（BUTTON_ADMIN_VERIFIED 引用）",
              has_str(jar, MB, "krg_btn_admin"))
        check(f"B4 {tag} dispatchAsClicker 保持 public（ServerSelectButtons 转发可用）",
              has_str(jar, SSB, "dispatchAsClicker"))
        check(f"B5 {tag} AI 管理三按钮门禁构造（adminGateFor 引用）",
              has_str(jar, PC, "adminGateFor"))
        check(f"B6 {tag} AI 管理门禁按钮 actionData 前缀 krg:acmd:/AI管理",
              has_str(jar, PC, "krg:acmd:/AI管理"))

    print("== C. 任务 B：MANUAL 模式 actionType=2 分支 ==")
    for tag, jar in JARS:
        # buttonFor 内 MANUAL 分支：非门禁且管理类 → actionType=2。
        # 字节码断言：MenuButtons 含 INPUT_COMMANDS 集合 + CommandAdminRegistry 引用
        check(f"C1 {tag} MenuButtons 引用 CommandAdminRegistry（按钮门禁判定）",
              has_str(jar, MB, "CommandAdminRegistry"))
        check(f"C2 {tag} INPUT_COMMANDS 集合在位（需补参命令）",
              has_str(jar, MB, "INPUT_COMMANDS"))
        check(f"C3 {tag} Keyboards.Button actionType 字段",
              has_str(jar, "cn/kerong/bot/tools/Keyboards$Button", "actionType"))
        # 源码级断言（三模块共享 common-Bot 源码，一处核对即可）
    mb_src = (REPO / "common-Bot" / "src" / "main" / "kotlin" / "cn" / "kerong" / "bot"
              / "events" / "MenuButtons.kt").read_text(encoding="utf-8")
    check("C4 源码 buttonFor MANUAL 分支（isManualAdminCommand 指令按钮）",
          "val isManualAdminCommand = !adminGate && !isInputCommand &&" in mb_src
          and "isManualAdminCommand -> entry.second" in mb_src)
    check("C5 源码 MANUAL 管理按钮 actionData 无前缀（填输入栏文本）",
          mb_src.count("entry.second\n") >= 1 and "isInputCommand -> entry.second" in mb_src)

    print("== D. 任务 C：全局按钮门禁 ==")
    for tag, jar in JARS:
        check(f"D1 {tag} ServerSelectButtons GLOBAL_MEMBER 常量", has_str(jar, SSB, "GLOBAL_MEMBER"))
        check(f"D2 {tag} 「全局」特权成员名常量值", has_str(jar, SSB, "全局"))
        check(f"D3 {tag} 全局按钮门禁回调 krg:acmd:/切换服务器",
              has_str(jar, SSB, "krg:acmd:/切换服务器"))
        check(f"D4 {tag} 全局按钮 MANUAL 指令形态 /切换服务器 填输入栏",
              has_str(jar, SSB, "/切换服务器 "))
        check(f"D5 {tag} onInteraction 转发合成命令（dispatchAsClicker 引用）",
              has_str(jar, SSB, "dispatchAsClicker"))
        check(f"D6 {tag} InterActionListener 调用带 userOpenId",
              has_str(jar, IAL, "userOpenId"))
        check(f"D7 {tag} InteractionFastPath 调用带 userOpenId",
              has_str(jar, IFP, "userOpenId"))
        check(f"D8 {tag} switchServer 带参模式（全局内联常量 + 切换日志）",
              has_str(jar, PC, "全局") and has_str(jar, PC, "带参切换服务器"))
        check(f"D9 {tag} switchServer 带参切换回复文案",
              has_str(jar, PC, "已切换到服务器"))
    pc_src = (REPO / "common-Bot" / "src" / "main" / "kotlin" / "cn" / "kerong" / "bot"
              / "events" / "commands" / "PublicCommands.kt").read_text(encoding="utf-8")
    check("D10 源码 switchServer 全局目标 requireAdmin",
          "GLOBAL_MEMBER" in pc_src and "requireAdmin" in pc_src)
    # const val 编译期内联到调用点（字节码无字段引用，断言查内联值"全局"）
    check("D11 源码 ServerSelectButtons.GLOBAL_MEMBER 常量定义",
          'const val GLOBAL_MEMBER = "全局"' in (
              REPO / "common-Bot" / "src" / "main" / "kotlin" / "cn" / "kerong" / "bot"
              / "events" / "ServerSelectButtons.kt").read_text(encoding="utf-8"))

    print("== E. 任务 D：菜单排版改版 ==")
    for tag, jar in JARS:
        check(f"E1 {tag} layoutRows 布局方法在位", has_str(jar, MB, "layoutRows"))
        check(f"E2 {tag} SHORT_LABEL_EXCEPTIONS 特例表（AI管理按 3 字计）",
              has_str(jar, MB, "SHORT_LABEL_EXCEPTIONS"))
        check(f"E3 {tag} COMMAND_ROWS_PER_PAGE 每页行数常量",
              has_str(jar, MB, "COMMAND_ROWS_PER_PAGE"))
        menu_md = res_bytes(jar, "Markdown/menu.md").decode()
        check(f"E4 {tag} menu.md 版本号移末尾非粗体小字",
              menu_md.rstrip("\n").endswith("v{version}") and "**" not in menu_md)
        check(f"E5 {tag} menu.md 保留分页标题", "### 指令菜单（{page}/{pages}）" in menu_md)
        check(f"E6 {tag} MessageTemplates menu 新默认收敛",
              has_str(jar, MT, "---\\nv{version}") or has_str(jar, MT, "---\nv{version}"))
    mt_src = (REPO / "common-Bot" / "src" / "main" / "kotlin" / "cn" / "kerong" / "penguin"
              / "spigot" / "qqbind" / "MessageTemplates.kt").read_text(encoding="utf-8")
    check("E7 源码收敛表收录 1.7.5.2 版旧默认",
          "### 指令菜单（{page}/{pages}）\\n\\n**当前插件版本：v{version}**" in mt_src)
    check("E8 源码收敛表新默认 = 末尾小字版本号",
          '"### 指令菜单（{page}/{pages}）\\n\\n---\\nv{version}"' in mt_src)

    print("== F. 回归（1.7.5.2 / 1.7.5.3 门禁随行） ==")
    MM = "cn/kerong/bot/MenuManager"
    for tag, jar in JARS:
        check(f"F1 {tag} 平台门禁回调前缀 krg:acmd:", has_str(jar, MB, "krg:acmd:"))
        check(f"F2 {tag} 门禁模式判定 adminGateFor", has_str(jar, MB, "adminGateFor"))
        check(f"F3 {tag} 注册表 adminOnly 标记",
              has_str(jar, "cn/kerong/bot/events/commands/BaseCommand", "CommandAdminRegistry"))
        check(f"F4 {tag} Button 权限类型字段",
              has_str(jar, "cn/kerong/bot/tools/Keyboards$Button", "permissionType"))
        check(f"F5 {tag} 面板联动 only_admin 随行",
              has_str(jar, MM, "only_admin") and has_str(jar, MM, "adminLinkageItems"))
        check(f"F6 {tag} config.yml panel-admin-linkage 默认开",
              "panel-admin-linkage: true" in res_bytes(jar, "config.yml").decode())
        check(f"F7 {tag} config.yml 配置版本 45（无新键）",
              "config-version: 45" in res_bytes(jar, "config.yml").decode())
        check(f"F8 {tag} GroupMemberLookup / MemberRoleCache 保持已删除",
              not entry_exists(jar, "cn/kerong/bot/events/GroupMemberLookup.class")
              and not entry_exists(jar, "cn/kerong/bot/events/MemberRoleCache.class"))

    return finish()


def finish():
    print()
    failed = [name for name, ok in checks if not ok]
    print(f"共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        print("失败项：")
        for name in failed:
            print(f"  - {name}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
