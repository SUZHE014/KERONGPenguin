#!/usr/bin/env python3
"""
门禁验证：1.7.5 覆盖第三轮（回调按钮真根因修复 group_id / 菜单去讲解字段 /
解除黑名单字段顺序 / 按钮按需补参分流 actionType=2 / 全量面板联动总配置 /
群级全量开关删除）。
对 build/dist 双端 JAR 与源码资源做字节级断言；基线为 penguin-work-base
（线上 1.7.5 二轮+翻页修复版，无 RecentGroupMessages）。
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPIGOT_JAR = ROOT / "build/dist/KERONGPenguin_Spigot-1.7.5.jar"
NF_JAR = ROOT / "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.jar"
BASE_JAR = Path("/home/z/my-project/penguin-work-base/KERONGPenguin_Spigot-1.7.5.jar")
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def class_bytes(z, path):
    try:
        return z.read(path)
    except KeyError:
        return b""


def disasm(z, path):
    """提取 class 到临时目录并 javap -p -c 反汇编（失败返回空文本）。"""
    out_dir = Path("/tmp/verify175o3")
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / path.replace("/", "_")
    if not target.exists():
        target.write_bytes(class_bytes(z, path))
    r = subprocess.run([JAVAP, "-p", "-c", str(target)], capture_output=True, text=True)
    return r.stdout


def main():
    assert SPIGOT_JAR.is_file(), f"缺少 Spigot JAR: {SPIGOT_JAR}"
    assert NF_JAR.is_file(), f"缺少 NeoForge JAR: {NF_JAR}"
    assert BASE_JAR.is_file(), f"缺少基线 JAR: {BASE_JAR}"
    zs = zipfile.ZipFile(SPIGOT_JAR)
    zn = zipfile.ZipFile(NF_JAR)

    # ============ A. 回调按钮无响应真根因修复（group_id + 被动回复通道） ============
    # 旧版合成事件 metadata 只写 group_openid，SDK getGroupOpenId() 实际读
    # group_id → 取 null → 群白名单拦截 → 命令从未执行（QQ 端已回应故显示
    # 操作成功）。现双字段齐写。
    menu_s = class_bytes(zs, "cn/kerong/bot/events/MenuButtons.class")
    menu_n = class_bytes(zn, "cn/kerong/bot/events/MenuButtons.class")
    check("A1 MenuButtons 双 JAR 在位", len(menu_s) > 500 and len(menu_n) > 500,
          f"spigot={len(menu_s)}B neoforge={len(menu_n)}B")
    check("A2 metadata 写 group_id 字段（真根因修复）", b"group_id" in menu_s and b"group_id" in menu_n)
    check("A3 metadata 写 group_openid 字段（发送目标）", b"group_openid" in menu_s)
    check("A4 metadata 写 author/member_openid（点击者身份）",
          b"member_openid" in menu_s and b"author" in menu_s)

    # 被动回复通道：RecentGroupMessages 新类（双端）+ 登记/读取接线
    rgm_s = class_bytes(zs, "cn/kerong/bot/events/RecentGroupMessages.class")
    rgm_n = class_bytes(zn, "cn/kerong/bot/events/RecentGroupMessages.class")
    check("A5 RecentGroupMessages 双 JAR 在位", len(rgm_s) > 500 and len(rgm_n) > 500,
          f"spigot={len(rgm_s)}B neoforge={len(rgm_n)}B")
    check("A6 TTL 常量 240000（4 分钟，被动窗口 5 分钟留余量）",
          b"\x80\xa0\x03\x00" in rgm_s or b"240000" in rgm_s or disasm(zs, "cn/kerong/bot/events/RecentGroupMessages.class").count("240000") > 0)
    check("A7 最近消息读取入口 latestId", b"latestId" in rgm_s and b"latestId" in menu_s)
    check("A8 真实消息登记入口 record", b"record" in rgm_s)
    gmh_s = class_bytes(zs, "cn/kerong/bot/events/GroupMessageHandler.class")
    gmh_n = class_bytes(zn, "cn/kerong/bot/events/GroupMessageHandler.class")
    check("A9 GroupMessageHandler 接线登记（双端）",
          b"RecentGroupMessages" in gmh_s and b"RecentGroupMessages" in gmh_n)
    check("A10 MenuButtons 引用 RecentGroupMessages", b"RecentGroupMessages" in menu_s)

    # msg_seq 防去重拒收：ReplySeqGuard.allocateBase 分配新基数
    check("A11 msg_seq 经 ReplySeqGuard.allocateBase 错开序号", b"ReplySeqGuard" in menu_s)
    check("A12 合成事件 setMsgSeq 在位", b"setMsgSeq" in menu_s)
    check("A13 合成事件仍走 dispatchSynthetic 正常命令分发", b"dispatchSynthetic" in menu_s)

    # javap 复核：dispatchAsClicker 方法体内 JSONObject.put("group_id", ...) 真实调用
    dis = disasm(zs, "cn/kerong/bot/events/MenuButtons.class")
    m = re.search(r"private final void dispatchAsClicker\(.*?\n  private", dis, re.S)
    body = m.group(0) if m else dis
    check("A14 javap: dispatchAsClicker 含 put group_id 调用", 'String group_id' in body and "put" in body)
    check("A15 javap: dispatchAsClicker 含 setMsgSeq 调用", "setMsgSeq" in body)

    # ============ B. 菜单重构（讲解字段全删，只留版本号） ============
    menu_md_s = zs.read("Markdown/menu.md").decode("utf-8")
    menu_md_n = zn.read("Markdown/menu.md").decode("utf-8")
    expected_menu = "### 指令菜单（{page}/{pages}）\n\n**当前插件版本：v{version}**"
    check("B1 menu.md 只留分页标题+版本行（双端一致）",
          menu_md_s.strip() == expected_menu and menu_md_n.strip() == expected_menu,
          repr(menu_md_s))
    check("B2 menu.md 无任何讲解文字",
          all(t not in menu_md_s for t in ("点击下方按钮", "复制到输入框", "补全内容", "带参数的指令")))
    # /菜单 命令仍走按钮流程
    pub_s = class_bytes(zs, "cn/kerong/bot/events/commands/PublicCommands.class")
    check("B3 /菜单 仍走 MenuButtons.sendFirstPage", b"sendFirstPage" in pub_s and b"MenuButtons" in pub_s)
    check("B4 纯文本兜底 plainList 在位", b"plainList" in pub_s)
    # 收敛：一轮版讲解文案 → 新默认（老安装自动升级）
    mt_s = class_bytes(zs, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class")
    check("B5 收敛表含一轮讲解版旧默认", "补全内容再发送".encode() in mt_s)
    check("B6 收敛表含二轮纯标题旧默认", "### 指令菜单（{page}/{pages}）".encode() in mt_s)
    check("B7 收敛新默认含版本行", "**当前插件版本：v{version}**".encode() in mt_s)

    # ============ C. 黑名单解除字段顺序（命令在前） ============
    unb_s = zs.read("Markdown/unblacklist-list.md").decode("utf-8")
    unb_n = zn.read("Markdown/unblacklist-list.md").decode("utf-8")
    expected_unb = "黑名单列表（{count} 个）：\n{list}\n\n发送 /解除黑名单 @对应的人 解除；也可以点击下方对应的按钮解除"
    check("C1 unblacklist-list 新默认：先 /解除黑名单 @对应的人（双端一致）",
          unb_s.strip() == expected_unb and unb_n.strip() == expected_unb, repr(unb_s))
    check("C2 旧「艾特对方发送」口径已换", "艾特对方" not in unb_s)
    check("C3 收敛表含二轮旧默认", "可以按下对应的按钮，或者艾特对方发送 /解除黑名单 解除".encode() in mt_s)
    check("C4 收敛表含 1.7.4 旧默认", "点击下方按钮即可解除对应条目".encode() in mt_s)
    unb_btn_s = class_bytes(zs, "cn/kerong/bot/events/UnblacklistButtons.class")
    check("C5 解除按钮竖排保持（buildVertical）", b"buildVertical" in unb_btn_s)

    # ============ D. 按钮分类：需补参命令复制进输入栏（actionType=2） ============
    # INPUT_COMMANDS = setOf(绑定/发信息/执行命令/执行/管理员执行)
    check("D1 INPUT_COMMANDS 集合构建（setOf 引用）", b"setOf" in menu_s)
    for cmd in ("绑定", "发信息", "执行命令", "管理员执行"):
        check(f"D2 INPUT_COMMANDS 含「{cmd}」", cmd.encode() in menu_s)
    # javap：pageKeyboard 方法体内有 actionType=2 分支（iconst_2 / bipush 2）
    dis_menu = disasm(zs, "cn/kerong/bot/events/MenuButtons.class")
    m2 = re.search(r"private final io\.github\.kloping\.qqbot\.entities\.ex\.Keyboard pageKeyboard\(.*?\n  private", dis_menu, re.S)
    pk = m2.group(0) if m2 else dis_menu
    check("D3 javap: pageKeyboard 含 actionType=2 分支", "iconst_2" in pk or "bipush          2" in pk)
    check("D4 javap: pageKeyboard 含 actionType=1 分支（回调）", "iconst_1" in pk)
    check("D5 javap: pageKeyboard 走 Set.contains 分流", "contains" in pk)
    # 指令按钮不设 enter（群聊自动发送仅单聊可用，enter=null 不调 setActionEnter）
    check("D6 MenuButtons 不调 setActionEnter（点击只填输入框不自动发送）",
          b"setActionEnter" not in menu_s)
    check("D7 Keyboards 仍统一 setActionType", b"setActionType" in class_bytes(zs, "cn/kerong/bot/tools/Keyboards.class"))
    check("D8 回调前缀 krg:cmd: 保持", b"krg:cmd:" in menu_s)
    check("D9 翻页前缀 krg:menu:page: 保持", b"krg:menu:page:" in menu_s)

    # ============ E. 全量按钮联动总配置（面板 + 菜单同步隐藏） ============
    mm_s = class_bytes(zs, "cn/kerong/bot/MenuManager.class")
    mm_n = class_bytes(zn, "cn/kerong/bot/MenuManager.class")
    check("E1 MenuManager 双 JAR 在位", len(mm_s) > 500 and len(mm_n) > 500)
    check("E2 快捷面板过滤「全量」按钮", "全量".encode() in mm_s)
    check("E3 面板过滤读 fullAmount（getFullAmount 引用）", b"getFullAmount" in mm_s)
    check("E4 菜单按钮同步读 fullAmount", b"getFullAmount" in menu_s)
    cfg_s = zs.read("config.yml").decode("utf-8")
    cfg_n = zn.read("config.yml").decode("utf-8")
    check("E5 config 注释改总开关口径（双端）",
          "总开关，对下方 bot.groups 里全部群统一生效" in cfg_s and "总开关，对下方 bot.groups 里全部群统一生效" in cfg_n)
    check("E6 commands.全量 注释含写回说明（双端）",
          "切换全量转发总开关" in cfg_s and "切换全量转发总开关" in cfg_n)

    # ============ F. 群级全量开关删除 → 只按总配置 ============
    check("F1 双端 config-version 43", "config-version: 43" in cfg_s and "config-version: 43" in cfg_n)
    cm_src = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt").read_text(encoding="utf-8")
    check("F2 ConfigManager CURRENT_CONFIG_VERSION = 43", "CURRENT_CONFIG_VERSION = 43" in cm_src)
    # StoredSettings：fullForwarding 字段删除
    ss_s = class_bytes(zs, "cn/kerong/bot/datapack/StoredSettings.class")
    check("F3 StoredSettings 无 fullForwarding 字段", b"fullForwarding" not in ss_s)
    # 仓储：setFullForwarding / fullForwardingSnapshot 删除
    gsr_s = class_bytes(zs, "cn/kerong/bot/state/CommandRepositories.class")
    check("F4 CommandRepositories 无 setFullForwarding", b"setFullForwarding" not in gsr_s)
    check("F5 CommandRepositories 无 fullForwardingSnapshot", b"fullForwardingSnapshot" not in gsr_s)
    # 状态文件：[full-forwarding] 段不再读写
    hrf_s = class_bytes(zs, "cn/kerong/bot/state/HumanReadableStateFile.class")
    check("F6 HumanReadableStateFile 无 full-forwarding 段", b"full-forwarding" not in hrf_s)
    # /全量 命令：切总配置并写回
    adm_s = class_bytes(zs, "cn/kerong/bot/events/commands/AdministrationCommands.class")
    check("F7 /全量 调 setFullAmountEnabled", b"setFullAmountEnabled" in adm_s)
    check("F8 /全量 不再引用 setFullForwarding", b"setFullForwarding" not in adm_s)
    # Spigot ConfigManager：写回链路
    cmc_s = class_bytes(zs, "cn/kerong/penguin/spigot/manager/ConfigManager.class")
    cmc_n = class_bytes(zn, "cn/kerong/penguin/spigot/manager/ConfigManager.class")
    check("F9 ConfigManager.setFullAmountEnabled（双端）",
          b"setFullAmountEnabled" in cmc_s and b"setFullAmountEnabled" in cmc_n)
    check("F10 写回 saveConfig + features.full-amount 路径",
          b"saveConfig" in cmc_s and b"features.full-amount" in cmc_s)
    # 主类接线（双端 override）
    ks_s = class_bytes(zs, "cn/kerong/penguin/spigot/KERONGSpigot.class")
    kn_s = class_bytes(zn, "cn/kerong/penguin/neoforge/KERONGNeoForge.class")
    check("F11 KERONGSpigot override setFullAmountEnabled", b"setFullAmountEnabled" in ks_s)
    check("F12 KERONGNeoForge override setFullAmountEnabled", b"setFullAmountEnabled" in kn_s)
    # 转发链路只看总配置
    check("F13 GroupMessageHandler 不再读 groupSettings.fullForwarding",
          b"fullForwarding" not in gmh_s and b"setFullForwarding" not in gmh_s)
    # 模板：总开关口径 + 收敛
    fat_s = zs.read("Markdown/full-amount-toggled.md").decode("utf-8")
    fat_n = zn.read("Markdown/full-amount-toggled.md").decode("utf-8")
    expected_fat = "全量转发已{state}（总开关，全部配置群统一生效{writeResult}）"
    check("F14 full-amount-toggled 新默认（双端一致）",
          fat_s.strip() == expected_fat and fat_n.strip() == expected_fat, repr(fat_s))
    check("F15 收敛表含群级旧默认「本群全量转发已{state}」", "本群全量转发已{state}".encode() in mt_s)
    # writeResult 占位符（写失败如实提示）
    check("F16 writeResult 占位符进模板", b"writeResult" in mt_s and b"writeResult" in adm_s)

    # ============ G. 基线不回归 ============
    names_s = set(zs.namelist())
    dirs_s = [n for n in names_s if n.endswith("/")]
    check("G1 Spigot 目录条目 776 基线", len(dirs_s) == 776, f"实际 {len(dirs_s)}")
    base = zipfile.ZipFile(BASE_JAR)
    base_names = set(base.namelist())
    added = names_s - base_names
    removed = base_names - names_s
    expected_added = {
        "cn/kerong/bot/events/RecentGroupMessages.class",
        "cn/kerong/bot/events/RecentGroupMessages$Entry.class",
    }
    check("G2 相对线上基线仅新增 RecentGroupMessages，无删减",
          added == expected_added and not removed,
          f"added={sorted(added)[:6]} removed={sorted(removed)[:6]}")
    check("G3 NF 重定位前缀在位", any(n.startswith("cn/kerong/shaded/") for n in zn.namelist()))
    py = zs.read("plugin.yml").decode("utf-8")
    check("G4 plugin.yml 版本 1.7.5", "version: '1.7.5'" in py or "version: 1.7.5" in py)
    mods_toml = zn.read("META-INF/mods.toml").decode("utf-8")
    neoforge_toml = zn.read("META-INF/neoforge.mods.toml").decode("utf-8")
    check("G5 mods.toml 双件版本 1.7.5", "1.7.5" in mods_toml and "1.7.5" in neoforge_toml)
    nf_main = class_bytes(zn, "cn/kerong/penguin/neoforge/KERONGNeoForge.class")
    check("G6 NF 主类 MOD_VERSION=1.7.5", b"1.7.5" in nf_main)
    # 二轮互动回应修复不回归
    acker_s = class_bytes(zs, "cn/kerong/bot/events/InteractionAcker.class")
    check("G7 InteractionAcker PUT 直发保持", b"PUT" in acker_s and b"/interactions/" in acker_s)
    fast_s = class_bytes(zs, "cn/kerong/bot/events/InteractionFastPath.class")
    check("G8 FastPath + 帧层接管保持", b"InteractionAcker" in fast_s)
    # 既有按钮流程不引入 enter
    psel_s = class_bytes(zs, "cn/kerong/bot/events/PlayerSelection.class")
    check("G9 既有选择按钮无 setActionEnter", b"setActionEnter" not in psel_s and b"setActionEnter" not in unb_btn_s)
    # 文本资源口径
    check("G10 JAR 文本资源无覆盖版字样", all(
        "覆盖版" not in zs.read(n).decode("utf-8", errors="ignore")
        for n in zs.namelist() if n.endswith((".md", ".yml", ".toml"))))
    check("G11 文本资源无版本新增标注", not any(
        re.search(r"（\d+\.\d+\.\d+新增）", zs.read(n).decode("utf-8", errors="ignore"))
        for n in zs.namelist() if n.endswith((".md", ".yml", ".toml"))))

    # ============ 汇总 ============
    failed = [r for r in results if not r[1]]
    print(f"\n===== 门禁 verify_jar_175o3：{len(results)} 项，通过 {len(results) - len(failed)}，失败 {len(failed)} =====")
    for name, ok, detail in results:
        mark = "PASS" if ok else "FAIL"
        line = f"[{mark}] {name}"
        if detail and not ok:
            line += f"  ({detail})"
        print(line)
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
