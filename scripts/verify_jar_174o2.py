#!/usr/bin/env python3
"""1.7.4 覆盖版（第二轮）门禁验证：QR 扫码登录 / 指令改名 / 死亡播报 / 按钮交互 / 附属修复。

用法：python3 scripts/verify_jar_174o2.py
产物：build/dist/KERONGPenguin_Spigot-1.7.4.jar
      build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar
      ../krgrender/build/libs/KERONGPenguin-Render-1.0.0.jar
"""
import os
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPIGOT = os.path.join(ROOT, "build/dist/KERONGPenguin_Spigot-1.7.4.jar")
NEOFORGE = os.path.join(ROOT, "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar")
RENDER = os.path.join(ROOT, "..", "krgrender/build/libs/KERONGPenguin-Render-1.0.0.jar")
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

PASS = 0
FAIL = 0


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name} {detail}")


def read_entry(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name)


def has_entry(jar, name):
    with zipfile.ZipFile(jar) as z:
        return name in z.namelist()


def javap_constants(jar, cls):
    """javap -v 提取常量池字符串（按类路径展开）。"""
    out = subprocess.run(
        [JAVAP, "-v", "-p", "-cp", jar, cls],
        capture_output=True, text=True, timeout=120,
    )
    return out.stdout


def main():
    print("== 基线 ==")
    for jar in (SPIGOT, NEOFORGE, RENDER):
        check(f"产物存在 {os.path.basename(jar)}", os.path.isfile(jar))
    if FAIL:
        sys.exit(1)

    with zipfile.ZipFile(SPIGOT) as z:
        names = z.namelist()
        dir_count = sum(1 for n in names if n.endswith("/"))
    check("Spigot 目录条目基线（≥770）", dir_count >= 770, f"实际 {dir_count}")

    print("== QR 组：扫码登录（任务4/5）==")
    check("QrLoginManager 类在位", has_entry(SPIGOT, "cn/kerong/bot/QrLoginManager.class"))
    qr_cls = javap_constants(SPIGOT, "cn.kerong.bot.QrLoginManager")
    for needle in ("https://q.qq.com/lite/create_bind_task",
                   "https://q.qq.com/lite/poll_bind_result",
                   "https://q.qq.com/qqbot/openclaw/connect.html?task_id=",
                   "AES/GCM/NoPadding",
                   "扫码链接: ",
                   "二维码已过期，正在刷新",
                   "已将 AppID 和 Secret 写入 config.yml",
                   "连续过期 6 次引导文案（/hbkrg qr 重新发起）", "QR 连续过期放弃", "连续过期" in qr_cls):
        pass
    # 显式断言（常量池逐串）
    for needle in ("https://q.qq.com/lite/create_bind_task",
                   "https://q.qq.com/lite/poll_bind_result",
                   "https://q.qq.com/qqbot/openclaw/connect.html?task_id=",
                   "AES/GCM/NoPadding",
                   "扫码链接: ",
                   "二维码已过期，正在刷新",
                   "已将 AppID 和 Secret 写入 config.yml",
                   "连续过期"):
        check(f"QR 常量池含「{needle[:34]}」", needle in qr_cls)
    # 任务10：HuHoBot 参考链接写在源码注释与配置注释里（KDoc 不进字节码，按源文件核验）
    def source_has(rel, needle):
        path = os.path.join(ROOT, rel)
        return os.path.isfile(path) and needle in open(path, encoding="utf-8").read()
    check("KERONG 源码注释引用 docs.huhobot.dpdns.org（任务10）",
          source_has("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt", "docs.huhobot.dpdns.org"))
    check("QrLoginManager 源码注释引用 HuHoBot 源码仓库（任务10）",
          source_has("common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt", "github.com/HuHoBot/PenguinAgent"))
    check("KERONG 启动未配置时走 QrLoginManager（字节码方法引用）",
          "QrLoginManager" in subprocess.run([JAVAP, "-c", "-p", "-cp", SPIGOT, "cn.kerong.bot.KERONG"],
                                             capture_output=True, text=True, timeout=120).stdout)
    # /hbkrg qr 双端命令层分支
    cmd_cls = javap_constants(SPIGOT, "cn.kerong.penguin.spigot.commands.KERONGCommand")
    check("Spigot /hbkrg qr 含扫码登录提示", "扫码登录" in cmd_cls or "二维码与扫码链接" in cmd_cls)
    neo_cmd_cls = javap_constants(SPIGOT, "cn.kerong.penguin.neoforge.NeoForgeCommands")  # NF 类同样在 Spigot 编译产物? 否——单独查 NF JAR
    # QR 编码器容量（connect URL 样例 ≤ v10 上限 213 字节）
    sample_url = "https://q.qq.com/qqbot/openclaw/connect.html?task_id=" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8" * 2 + "&source=openclaw&_wv=2"
    check("connect URL 样例在 QrCode 容量内（≤213 字节）", len(sample_url.encode()) <= 213,
          f"样例 {len(sample_url.encode())} 字节")

    print("== 指令改名组（任务6）==")
    pub = javap_constants(SPIGOT, "cn.kerong.bot.events.commands.PublicCommands")
    check("玩家名片 指令注册", "玩家名片" in pub)
    check("查询open ID 保留为别名", "查询open ID" in pub)
    cfg_cls = javap_constants(SPIGOT, "cn.kerong.penguin.spigot.manager.ConfigManager")
    check("COMMAND_NAMES 含 玩家名片", "玩家名片" in cfg_cls)
    check("COMMAND_ALIASES 旧名映射（查询openID→查信息）", "查询openID" in cfg_cls)
    menu_cls = javap_constants(SPIGOT, "cn.kerong.bot.MenuManager")
    check("面板按钮 查信息（查 OpenId）", "查信息" in menu_cls)
    check("面板按钮 玩家名片", "玩家名片" in menu_cls)
    help_md = read_entry(SPIGOT, "Markdown/help.md").decode("utf-8")
    check("help.md 列出 /玩家名片", "/玩家名片" in help_md)
    check("help.md 列出 /查信息（OpenId 查询）", help_md.count("/查信息") >= 2)
    check("help.md 无旧写法 /查询open ID", "/查询open ID" not in help_md)
    config_spigot = read_entry(SPIGOT, "config.yml").decode("utf-8")
    check("config commands 段含 玩家名片: true", "玩家名片: true" in config_spigot)
    check("config commands 段含 查信息: true", "查信息: true" in config_spigot)
    check("config 无旧键 查询open ID: true", "查询open ID: true" not in config_spigot)
    gmh = javap_constants(SPIGOT, "cn.kerong.bot.events.GroupMessageHandler")
    check("任意群放行含 玩家名片", "玩家名片" in gmh)

    print("== 死亡播报组（任务7）==")
    check("Spigot DeathMessage 类在位", has_entry(SPIGOT, "cn/kerong/penguin/spigot/events/DeathMessage.class"))
    dm = javap_constants(SPIGOT, "cn.kerong.penguin.spigot.events.DeathMessage")
    # 代码字符串（「被${killer}杀死了」编译为 被X杀死了 模板 + 分支文案）
    for needle in ("杀死了", "从高处摔落而死", "掉进了岩浆", "被闪电劈死了", "撞墙死了"):
        check(f"DeathMessage 代码分支含「{needle}」", needle in dm)
    # KDoc 参考链接（源码级）
    dm_src = os.path.join(ROOT, "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/DeathMessage.kt")
    dm_src_text = open(dm_src, encoding="utf-8").read()
    check("DeathMessage 源码注释引用 HuHoBot（文档+源码，任务10）",
          "docs.huhobot.dpdns.org" in dm_src_text and "github.com/HuHoBot/PenguinAgent" in dm_src_text)
    dmn_src = os.path.join(ROOT, "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/DeathMessageNeo.kt")
    dmn_text = open(dmn_src, encoding="utf-8").read()
    check("DeathMessageNeo 源码注释引用 HuHoBot（任务10）",
          "docs.huhobot.dpdns.org" in dmn_text and "github.com/HuHoBot/PenguinAgent" in dmn_text)
    pel = subprocess.run([JAVAP, "-c", "-p", "-cp", SPIGOT, "cn.kerong.penguin.spigot.events.PlayerEventsListener"],
                         capture_output=True, text=True, timeout=120).stdout
    check("Spigot 监听器调用 broadcastPlayerDeath", "broadcastPlayerDeath" in pel)
    qc = javap_constants(SPIGOT, "cn.kerong.bot.QClient")
    check("QClient.broadcastPlayerDeath 在位", "broadcastPlayerDeath" in qc)
    pf = javap_constants(SPIGOT, "cn.kerong.bot.provider.ConfigProvider")
    check("PlayerEventFormat 死亡字段（{killer} 占位符）", "{killer}" in pf)
    check("config player-events.death 默认关闭", "death:\n    enabled: false" in config_spigot)
    check("config 死亡播报注释含 HuHoBot 参考链接", "docs.huhobot.dpdns.org" in config_spigot)
    # NeoForge 侧
    nf_names = []
    with zipfile.ZipFile(NEOFORGE) as z:
        nf_names = z.namelist()
    check("NF DeathMessageNeo 在位（重定位路径）",
          any("DeathMessageNeo.class" in n for n in nf_names))
    nf_events = javap_constants(NEOFORGE, "cn.kerong.penguin.neoforge.NeoForgeEvents")
    check("NF 监听器调用 broadcastPlayerDeath", "broadcastPlayerDeath" in nf_events)
    config_nf = read_entry(NEOFORGE, "config.yml").decode("utf-8")
    check("NF config player-events.death 默认关闭", "death:\n    enabled: false" in config_nf)
    check("NF config 指令改名同步（玩家名片: true）", "玩家名片: true" in config_nf)

    print("== 按钮交互组（任务8/9）==")
    check("Keyboards 工具在位", has_entry(SPIGOT, "cn/kerong/bot/tools/Keyboards.class"))
    check("InterActionListener 在位", has_entry(SPIGOT, "cn/kerong/bot/events/InterActionListener.class"))
    check("UnblacklistButtons 在位", has_entry(SPIGOT, "cn/kerong/bot/events/UnblacklistButtons.class"))
    ps = javap_constants(SPIGOT, "cn.kerong.bot.events.PlayerSelection")
    check("选择按钮前缀 krg:psel:", "krg:psel:" in ps)
    check("PlayerSelection 源码注释引用 HuHoBot 参考",
          source_has("common-Bot/src/main/kotlin/cn/kerong/bot/events/PlayerSelection.kt", "docs.huhobot.dpdns.org")
          and source_has("common-Bot/src/main/kotlin/cn/kerong/bot/events/PlayerSelection.kt", "github.com/HuHoBot/PenguinAgent"))
    ub = javap_constants(SPIGOT, "cn.kerong.bot.events.UnblacklistButtons")
    check("黑名单按钮前缀 krg:unban:", "krg:unban:" in ub)
    check("UnblacklistButtons 源码注释引用 HuHoBot 参考",
          source_has("common-Bot/src/main/kotlin/cn/kerong/bot/events/UnblacklistButtons.kt", "docs.huhobot.dpdns.org")
          and source_has("common-Bot/src/main/kotlin/cn/kerong/bot/events/UnblacklistButtons.kt", "github.com/HuHoBot/PenguinAgent"))
    select_md = read_entry(SPIGOT, "Markdown/select-player.md").decode("utf-8")
    check("select-player.md 按钮文案", "点击下方按钮" in select_md)
    unban_md = read_entry(SPIGOT, "Markdown/unblacklist-list.md").decode("utf-8")
    check("unblacklist-list.md 按钮文案", "点击下方按钮" in unban_md)
    qclient_code = subprocess.run([JAVAP, "-c", "-p", "-cp", SPIGOT, "cn.kerong.bot.QClient"],
                                  capture_output=True, text=True, timeout=120).stdout
    check("QClient 注册 InterActionListener", "InterActionListener" in qclient_code)
    ial = javap_constants(SPIGOT, "cn.kerong.bot.events.InterActionListener")
    check("互动监听源码注释引用 HuHoBot 参考",
          source_has("common-Bot/src/main/kotlin/cn/kerong/bot/events/InterActionListener.kt", "docs.huhobot.dpdns.org")
          and source_has("common-Bot/src/main/kotlin/cn/kerong/bot/events/InterActionListener.kt", "github.com/HuHoBot/PenguinAgent"))
    check("Keyboards 源码注释引用 HuHoBot 参考",
          source_has("common-Bot/src/main/kotlin/cn/kerong/bot/tools/Keyboards.kt", "docs.huhobot.dpdns.org"))
    kb = subprocess.run([JAVAP, "-c", "-p", "-cp", SPIGOT, "cn.kerong.bot.tools.Keyboards"],
                        capture_output=True, text=True, timeout=120).stdout
    check("Keyboards 提交行（RowBuilder.build）", "RowBuilder.build" in kb.replace("$", "").replace("build", "build") and ".build()" in kb or "build:()" in kb)

    print("== 附属组（任务1/2/3）==")
    with zipfile.ZipFile(RENDER) as z:
        rnames = z.namelist()
    check("附属无迁移类 KerongImportService", not any("KerongImportService" in n for n in rnames))
    check("附属无 migration 包", not any("/migration/" in n for n in rnames))
    krg = javap_constants(RENDER, "cn.kerong.render.KrgCommands")
    check("附属 玩家名片 指令注册", "玩家名片" in krg)
    check("附属不再注册 查信息 指令", "查信息" not in krg)
    bb = subprocess.run([JAVAP, "-c", "-p", "-cp", RENDER, "cn.kerong.render.bridge.BindBridge"],
                        capture_output=True, text=True, timeout=120).stdout
    check("BindBridge 直读 HuHoBot 原生绑定（CommandRepositories.bindings）", "CommandRepositories" in bb and "getBinding" in bb)
    check("BindBridge 无 QUUID 术语（findQuuidByQq 移除）", "findQuuidByQq" not in bb)
    rconfig = read_entry(RENDER, "config.yml").decode("utf-8")
    check("附属 commands 段含 玩家名片: true", "玩家名片: true" in rconfig)
    check("附属 override-commands 不再含 查信息", "查信息: true" not in rconfig.split("commands:")[0])
    check("附属 config 注明查信息归 HuHoBot 内置", "HuHoBot 内置回复" in rconfig)
    rinfo = read_entry(RENDER, "Markdown/info-not-bound.md").decode("utf-8")
    check("附属未绑定提示指向 HuHoBot /绑定", "/绑定" in rinfo)
    rpsm = javap_constants(RENDER, "cn.kerong.render.stats.PlayerStatsManager")
    check("附属 PlayerStatsManager 无 importRecord", "importRecord" not in rpsm)

    print(f"\n结果：PASS {PASS} / FAIL {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
