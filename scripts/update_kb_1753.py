#!/usr/bin/env python3
"""update_kb_1753：知识库更新（1.7.5.2 覆盖版 + 1.7.5.3 + fa 1.7.5.3 + 1.7.5.1 下架）。

本地改 /tmp/KB.txt → 上传服务器两处（/wwwroot/penguin/ + /SUZHE014/）。
"""
import os

import paramiko

NEW_ENTRY = """> **本轮 v1.7.5.2 覆盖版 + v1.7.5.3 + fa 1.7.5.3（2026-11-12，三 JAR 已上线）**：用户否决 1.7.5.2 初版的官方接口方案（GroupMemberLookup 实时查询需申请平台白名单接口，"申请还要麻烦的一大堆"），① **1.7.5.2 覆盖版（同号重发）——按钮管理员判定改平台门禁（零接口零缓存）**：GroupMemberLookup 整体删除；@Commands 注解新增 **adminOnly** 参数 + **CommandAdminRegistry**（单一事实来源：执行命令/管理员执行/全量/AI管理/黑名单/解除黑名单/解除认证 七命令标记，认证（带参才管理）与执行（自定义命令公开入口）不误标；BaseCommand 扫描自动登记，菜单按钮与快捷面板两层自动跟随，无需平台白名单或手动配置）；admin.mode=qq/both 下管理类**回调按钮**以 **permission.type=1** 下发 + 回调前缀改 **krg:acmd:**（点击到达 = QQ 客户端已实时验身，撤权立即点不动、回调根本不到达机器人），dispatchAsClicker 把 member_role=admin 写进合成事件与手打判定一致；管理类**指令按钮**（执行命令/管理员执行，点击复制进输入栏）同加权限门禁但 actionData 不变（命令手动发送走正常消息路径自带真实角色）；admin.mode=config 下按钮人人可点（Keyboards.Button 新增 permissionType 字段，原硬编码 2），由命令链路 requireAdmin 按 admin.openids 名单实时判定（名单增删立即生效）；群模式切换后旧菜单点击由命令链路按当前模式重判不因平台旧门禁放行；InteractionFastPath/InterActionListener 双路由接入 acmd 前缀；**已知边界**：both 模式下仅名单内（非群管）管理员点不动管理按钮（平台权限字段只认群角色无法表达并集），手打命令不受影响，补救是给群管身份。② **1.7.5.3——QQ 快捷面板管理员联动**（用户需求："配置文件里写的管理员判定处于QQ群管理员/全部状态的时候，自动把QQ快捷指令面板的仅管理员命令调为仅对管理员显示，有单独的配置默认开"）：新键 **admin.panel-admin-linkage**（默认 true），开启且 admin.mode=qq/both 时 MenuManager 面板同步给管理类条目下发 **only_admin=true**（与菜单按钮同一注册表标记），QQ 端仅群主/群管理员可见可点；config 模式或联动关闭时全员可见、命令链路按名单判定；改动后 /hbkrg reload 随面板重同步生效（启动/reload/全量切换三时机）；config-version 44→45（只加新键）。③ **1.7.5.1 整版下架**（用户指令"把1.7.5.1版本删了"）：官网历史表三行（Spigot/NeoForge/Fabric 1.7.5.1）+ 更新日志 v1.7.5.1 块 + 服务器三个旧 JAR 全删，v1.7.5.3 更新日志注明下架原因（按钮管理员 30 分钟缓存方案已被覆盖取代）；其已落地且仍在产物中的修复（被动回复轮转/模板升级放宽/回应超时收紧/全量面板刷新/fa 主线化与绑定不拦截）不受影响，知识留档见 §12。④ **fa 版 1.7.5.3 随主线**：内核对齐 1.7.5.2 覆盖版+1.7.5.3 全部改动；**Fabric 平台 config.yml 补欠账**（fa 1.7.5.1 停在 43——主线 44 的 kick-message 绑定码新默认当时未随行，本轮 43→45 一步补齐：绑定码口径默认 + 单行写法注释 + panel-admin-linkage 新键）；fabric.mod.json 1.7.5.1→1.7.5.3。验证：门禁 verify_jar_1752 D 段重写后 **77 项** + verify_jar_1753 **33 项** + verify_jar_1753_fa **23 项** + 冒烟 smoke_1752（增第 6 节按钮门禁 5 项）**25 项** + smoke_1753（增 P1-P5 面板联动）**30 项** + 外网 verify_online_1753 **20 项**全绿；git push main=`9e43084`；五 JAR 上线 md5 往返一致（1.7.5.3：Spigot 7183a8c7 / NeoForge a3ffbfd3 / Fabric f634815a；1.7.5.2 覆盖版：Spigot d8722d89 / NeoForge e9970c8f）。**本轮环境新坑（沙盒第四次重建）**：BMCLAPI 的 /maven 段 2026-11 起**不再回源 net.neoforged / net.minecraftforge 树**（metadata 仍可读但构件一律 404，cernet 节点同 404）——build_neoforge_libs.py 已改多镜像容错（BMCLAPI → **CleanroomMC maven**（maven.cleanroommc.com，全量代理 neoforged/forge，同路径直代）→ 原始 URL）+ ART 改用 **Maven Central 的 net.neoforged:AutoRenamingTool 2.0.12**（Central 还托管 bus；loader 与 neoforge universal 只在 CleanroomMC 有）；**build_fabric_libs.py 编译 MergeMappings 必须用 javac**（java 源文件模式 java Foo.java 会直接执行程序，无参即 ArrayIndexOutOfBounds 崩溃，已加 find_javac 修复）；package_jar_fabric.py 的重映射产物有缓存（deps-work/fabric-libs/package/server-Fabric-intermediary.jar——**改配置/资源后必须删缓存重打包**，否则分发 JAR 里是旧 config，本轮 Fabric config 45 就这样翻车一次）；系统 java 是 JRE 无 javac 且无 root apt——便携 JDK 从**华为镜像 mirrors.huaweicloud.com/openjdk/21.0.2** 拉（tooling/jdk-21.0.2，Gradle 需 -Dorg.gradle.java.installations.paths=/usr/lib/jvm/java-21-openjdk-amd64）；GitHub 直连 push 本轮可用（令牌嵌 URL）无需 ghfast 代理。
"""

NEW_S12 = """- **1.7.5.3 + 1.7.5.2 覆盖版 + fa 1.7.5.3（2026-11-12）**：覆盖版——GroupMemberLookup 删除，按钮管理员判定改平台门禁（@Commands adminOnly + CommandAdminRegistry 注册表标记；qq/both 下管理类按钮 permission.type=1 + krg:acmd: 前缀，点击到达即平台已验身撤权立即点不动；config 下按 admin.openids 名单实时判定；Keyboards.Button permissionType 字段透传）；1.7.5.3——快捷面板管理员联动（admin.panel-admin-linkage 默认 true：qq/both 下管理类面板条目 only_admin=true，注册表同源标记，/hbkrg reload 随面板重同步；config-version 44→45）；1.7.5.1 下架（官网三行+日志块+三个旧 JAR 删除）；fa 1.7.5.3 随主线（Fabric config.yml 补欠账 43→45：主线 44 的 kick-message 绑定码默认当时未随行）；门禁 77+33+23 / 冒烟 25+30 / 外网 20 全绿；md5：1.7.5.3 Spigot 7183a8c7 / NeoForge a3ffbfd3 / Fabric f634815a，1.7.5.2 覆盖 Spigot d8722d89 / NeoForge e9970c8f；git push main=9e43084。
"""


def main():
    with open("/tmp/KB.txt", encoding="utf-8") as f:
        lines = f.readlines()

    out = []
    i = 0
    dropped_1751 = 0
    while i < len(lines):
        line = lines[i]
        # 1) 在 v1.7.5.2 条目（2026-11-11）前插入新条目
        if line.startswith("> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-11（**v1.7.5.2 六项**"):
            out.append(NEW_ENTRY)
            out.append("\n")
            out.append(line)
            i += 1
            continue
        # 2) 删除 1.7.5.1 两条头部条目（覆盖版+fa 入主线 / 原版）
        if line.startswith("> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-11（**v1.7.5.1 覆盖版 + fa 入主线**"):
            dropped_1751 += 1
            i += 1
            continue
        if line.startswith("> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-10（**v1.7.5.1**"):
            dropped_1751 += 1
            i += 1
            continue
        # 3) §12：头部插入本轮条目 + 1.7.5.1 条目加下架标注
        if line.startswith("- **1.7.5.2（2026-11-11，六项"):
            out.append(NEW_S12.rstrip("\n") + "\n")
            out.append(line)
            i += 1
            continue
        if line.startswith("- **1.7.5.1（2026-11-10 首发 + 2026-11-11 覆盖版 + fa 入主线"):
            out.append("- **1.7.5.1（已下架 2026-11-12，用户指令——按钮管理员 30 分钟缓存方案已被 1.7.5.2 覆盖版平台门禁取代；其已落地修复仍在产物中；知识留档）（2026-11-10 首发 + 2026-11-11 覆盖版 + fa 入主线，同版本号直接覆盖）**：" + line.split("，同版本号直接覆盖）**：", 1)[1])
            i += 1
            continue
        out.append(line)
        i += 1

    text = "".join(out)

    # 4) §0 版本规则段重写（当前版本 → 1.7.5.3）
    import re
    pattern = re.compile(r"\*\*版本规则\*\*：插件版与模组版同步发号。当前版本 .*?（详见或见 §12|config-version \*\*44\*\*.*?）\*\*。", re.S)
    m = pattern.search(text)
    assert m, "§0 版本规则段未匹配"
    new_para = (
        "**版本规则**：插件版与模组版同步发号。当前版本 **1.7.5.3（2026-11-12）**"
        "（1.7.5.3 一项：QQ 快捷面板管理员联动——admin.panel-admin-linkage（默认开）且 admin.mode=qq/both 时，"
        "快捷面板管理类指令条目（执行命令/管理员执行/全量/AI管理/黑名单/解除黑名单）自动设为仅群主/群管理员可见可点"
        "（面板 only_admin，注册表 adminOnly 同源标记，与 /菜单 管理按钮同一口径）；config 模式或联动关闭时全员可见、"
        "命令按名单判定；改动后 /hbkrg reload 随面板重同步生效；config-version 44→45 新增一键）。"
        "前版 **1.7.5.2（2026-11-11 首发 + 同日覆盖版重发）**（六项：① 菜单模板去强制更新（menu 回升级表 + BOM 容忍）；"
        "② 回应连接复用 + ConnectionWarmer 4 秒保活（「第三方连接失败」残余修复）；"
        "③ 按钮管理员判定**平台门禁**（**覆盖版口径**：GroupMemberLookup 接口查询删除，改 @Commands adminOnly 注册表标记 + "
        "permission.type=1/krg:acmd: 平台门禁 + 名单兜底，撤权立即点不动、零接口零缓存）；④ kick-message 默认回绑定码口径；"
        "⑤ QQ 绑定开启自动关服务器白名单（双端四时机）；⑥ kick-message 单行 \\n 写法；config-version 43→44）。"
        "**1.7.5.1 已下架（2026-11-12 用户指令，官网历史表/日志/旧 JAR 全删）**——其按钮管理员 30 分钟缓存方案被 1.7.5.2 覆盖版取代；"
        "已落地修复（被动回复轮转 / 模板升级放宽 / 回应超时收紧 / 全量面板刷新 / fa 主线化与绑定不拦截）仍在产物中，知识见 §12。"
        "前版 **1.7.5（2026-11-10，覆盖第三轮）**（/菜单 按钮菜单 + 互动回应 PUT 直发 + group_id 真根因 + 回复被动通道 + "
        "AI管理合并 + 黑名单按钮化 + 全量总开关，详见 §12）。前版 **1.7.4.1 / 1.7.4**（见 §12）。"
        "config-version **45**（1.7.5 链 40→41→42→43→44→45：commands 迁移、模板新键、注释刷新、全量总开关、"
        "kick-message 旧默认收敛、panel-admin-linkage 新键）。"
        "**fa 版（Fabric）当前 1.7.5.3 随主线同号发版**（1.7.5.3 起完全同步：按钮平台门禁 + 面板联动 + Fabric config.yml 补欠账 43→45；"
        "构建管线 build_fabric_libs.py + package_jar_fabric.py + fabric-tools/MergeMappings.java 已入库，"
        "关键坑（三列合并映射 / javac 编译 / 重映射缓存）见头部与 §12；"
        "fabric 原料在服务器 /root/fabric-deps/ 与本地 fabric-deps/，1.20.1 下探评估见 server-Fabric/README.md）。"
    )
    text = text[:m.start()] + new_para + text[m.end():]

    # 5) git push 状态行补本轮
    text = text.replace(
        "**git push 状态**：✅ 已完成（2026-11-10 v1.7.5.1 实测，API 复核远端 main = `5484b6d`）",
        "**git push 状态**：✅ 已完成（2026-11-12 v1.7.5.2覆盖+1.7.5.3+fa 实测，远端 main = `9e43084`；本轮 GitHub 直连 push 可用（令牌嵌 URL 无需 ghfast 代理）。",
        1,
    )

    with open("/tmp/KB.txt", "w", encoding="utf-8") as f:
        f.write(text)
    print(f"本地更新完成（删 1.7.5.1 头部条目 {dropped_1751} 条）")

    # 上传两处
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.248", 22, "root", os.environ["PENGUIN_SSH_PASS"], timeout=25)
    sftp = c.open_sftp()
    sftp.put("/tmp/KB.txt", "/wwwroot/penguin/AI-KNOWLEDGE.txt")
    sftp.put("/tmp/KB.txt", "/SUZHE014/AI-KNOWLEDGE.txt")
    _, out2, _ = c.exec_command("chown www:www /wwwroot/penguin/AI-KNOWLEDGE.txt && wc -l /wwwroot/penguin/AI-KNOWLEDGE.txt /SUZHE014/AI-KNOWLEDGE.txt", timeout=30)
    print(out2.read().decode())
    c.close()


if __name__ == "__main__":
    main()
