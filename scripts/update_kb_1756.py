#!/usr/bin/env python3
"""1.7.5.6 轮知识库更新:更新日志.md(状态表+本轮节,替换 1.7.5.5 节)+ 会话上下文.md(新节替换旧节)。
按 2026-11-13 用户指令:知识库只保留最新的版本(单轮)。"""
import os
import sys
import paramiko

HOST = "156.233.228.248"
PASS = os.environ.get("PENGUIN_SSH_PASS", "")
BASE = "/home/z/my-project/kb3/精简"

LOG_TAIL = """## 本轮详细更新日志(1.7.5.6 Fa 修复轮;更早版本全量历史见 git 提交历史)

### 2026-11-13 22:00-00:10:1.7.5.6(Fa 修复轮:Fabric 1.20.1 支持 / hbkrg qr 全新码 / 远程命令 brigadier 直发)

用户指令四项(会话续接):知识库只保留最新版本 + 修复 Fa 二维码问题 + Fa 支持 1.20.1 + 用户日志定性(1.20.4+1.7.4.1 的 hbkrg qr 崩溃 NoSuchMethodError)。

**[22:00-22:10] 知识库精简(先做,用户指定)**:三件套只留最新版——更新日志.md 状态表+1.7.5.5 节(41 行)、会话上下文.md 头部+最新节(31 行)、AI交接文档.md 删同步记录历史留指针+修正「server-Fabric 不进主线 git」过时口径为已入库(349 行);三件套上传 md5 往返一致;服务器 .bak 旧备份 6 个全删;目录从 1196 行瘦身至 422 行。头部口径「只保留最新 1 轮(2026-11-13 用户指令)」三处同步。

**[22:10-23:20] 问题定性(代码+字节码+真机)**:①用户日志的 NoSuchMethodError(class_2561.literal)来自 **1.7.4.1 测试版 jar**——旧打包流程类名已 remap 但方法名漏 remap;1.7.5.1 起入主线构建已修复。本轮 1.20.4 真机(fa1204 环境,Loader 0.15.1+fabric-api 0.97.3)复验:未配置凭据/已配置凭据两场景 hbkrg qr 均正常单码无异常。②「发送 hbkrg qr 出 3 个二维码」定位:旧实现手动发起=重显旧码且旧会话的过期自动刷新继续叠加(每会话上限 3 次),观感即多个二维码。③静态核对发现 1.20.1 的 intermediary 与 1.20.4 存在结构性漂移:performPrefixedCommand 返回 int→void(1.20.2 命令执行链改)、踢人 disconnect 定义类错位(1.20.1 在 class_3244 本类,1.20.4 继承自 class_8609)——1.20.4 remap 的 jar 直接放 1.20.1 必崩,必须单独重映射。

**[23:20-23:45] 代码实施**:①QrLoginManager.start 手动分支改「先取消旧会话再新建」(旧轮询线程下一轮检查自行退出,一次命令恰好一个全新二维码;启动自动路径维持重显);②FabricConsoleExecutor 弃用 performPrefixedCommand 改 brigadier dispatcher.execute(独立库 API 1.19–1.21 签名稳定;getDispatcher 两版 intermediary 同为 method_9235)+自行剥前缀+CommandSyntaxException sendFailure;③版本号 1.7.5.5→1.7.5.6 六处,config-version 46 保持。

**[23:45-00:00] 1.20.1 打包链**:scripts/build_fabric_1201_map.py 生成 merged-1201.tiny(1.20.1 官方 client.txt+intermediary 合并+继承错位补丁:class_8609 节补 disconnect→method_14367;**首轮补丁把假类行插在 class_3244 类行后切断原节,player 等字段全部查表失败,门禁 C3 抓出后改为插在节末/下一类行前修正**);材料备份服务器 /root/fabric-deps/1201/(client-1.20.1.txt/intermediary-1.20.1-v2.jar/merged-1201.tiny);package_jar_fabric.py 参数化 --mc/--merged(MC_PROFILES)+非缺省版本重写 fabric.mod.json minecraft 声明(1.20.1 精确锁定,1.20.2/1.20.3 由加载器拦截)。

**[00:00-00:05] 构建与门禁**:gradlew clean 四模块 BUILD SUCCESSFUL(2m33s);四 JAR 打包(Spigot 底座=线上 1.7.5.5;NF/Fa×2 底座=本版 Spigot);门禁 verify_jar_1756 **59/59 全绿**(A 版本资源×4/B 手动取消文案+brigadier 引用/C Fa1201 disconnect→method_14367+Fa1204→method_52396+未映射引用白名单 4 条/D 1.7.5.4-5 回归/E 形态)。

**[00:05-00:10] 三环境真机验证**:fa1204(1.20.4)加载+qr×2 验证全新码语义(第二次输出「已取消此前的扫码会话」+全新 task_id);fa1201(1.20.1+Loader 0.16.14+fabric-api 0.92.12+1.20.1——0.92.12 要求 Loader≥0.16.10,0.15.1 会 Incompatible,已换 0.16.14)加载+自动码+手动 qr 全新码+hbkrg help 正常,运行时 server-intermediary.jar javap 确认 method_14367/method_9235 存在;fa121(1.21+Loader 0.20.1)加载+qr 正常。md5:Spigot b7d2dbcb/NeoForge 8721c7ba/Fabric-1.20.4 d9fdb397/Fabric-1.20.1 cc1d82c8。

**[00:10] 部署+push**:四 JAR+官网三件(index 主框四链接/Fabric 核心口径 1.20.1 与 1.20.4–1.21.x/历史表四行/置顶日志块两条短句;version.json 四条含 1.7.4.1 升级提示;tutorial qr 新语义+版本选择段)上传 chown www:www chmod 644 md5 往返一致;外网复核全过(四 JAR 200+md5/官网关键元素/tutorial 新文案);git push main=741449b(+fa88c0e 源码包脚本名修正);源码包 KERONGPenguin_v1.7.5.6_source.zip 9.03MB/825 文件(md5 da702156)传 /wwwroot/penguin-source/。

- **给用户的口径**:仍在用 Fabric 1.7.4.1 的服务器升级到 1.7.5.6 即解决崩溃;1.20.1 服务器下载独立包 KERONGPenguin_Fabric-1.20.1-1.7.5.6.jar(fabric-api 用 0.92.x+1.20.1,Loader ≥0.16.10 或更高)。
"""

LOG_TABLE = """## 当前版本状态(更新于 2026-11-14 00:15)

| 项 | 状态 |
|---|---|
| 当前版本 | **1.7.5.6**(Fa 修复轮:Fabric 1.20.1 独立包 / 手动 hbkrg qr 全新二维码 / 远程命令 brigadier 直发;config-version 46 保持无新键) |
| 四端产物 | KERONGPenguin_Spigot-1.7.5.6.jar(md5 b7d2dbcb…)/ KERONGPenguin_NeoForge-1.20.4-1.7.5.6.jar(8721c7ba…)/ KERONGPenguin_Fabric-1.20.4-1.7.5.6.jar(d9fdb397…)/ **KERONGPenguin_Fabric-1.20.1-1.7.5.6.jar(cc1d82c8…)**(1.20.1 独立包)(/wwwroot/penguin/downloads/,md5 往返一致,外网 200 验证过) |
| config-version | 46(无新键无迁移) |
| git push | ✅ GitHub main = **fa88c0e**(741449b 主体 + fa88c0e 源码包脚本名修正;ghfast.top 代理 + classic ghp_ GIT_ASKPASS;api.github.com 复核) |
| 官网 | version.json(1.7.5.6 四条)/ index.html(主框四下载链接含 1.20.1 包 + Fabric 核心口径「1.20.1 与 1.20.4–1.21.x」+ 历史表四行 + 置顶日志块两条短句)/ tutorial.html(qr 命令行新语义 + 版本选择段 Fabric 口径)上线;源码包 KERONGPenguin_v1.7.5.6_source.zip(9.03MB/825 文件,HEAD 741449b,md5 da702156)传 /wwwroot/penguin-source/ |
| MC 服务端 | ⚠️ 不在 156.233.228.248 上;用户在实际服务器替换 JAR 并重启后生效(Fabric 1.20.1 服务器请下载独立包;仍在用 1.7.4.1 测试版的必须升级——旧版 hbkrg qr 会崩溃) |
| fa 版本 | Fabric 1.7.5.6 两产物:通用包(1.20.4–1.21.x)+ 1.20.1 独立包(仅限 1.20.1;1.20.2/1.20.3 不支持,加载器按声明拦截) |
| Fa 遗留 | ✅ 全部闭环:1.7.4.1 崩溃=旧打包流程方法名漏 remap(1.7.5.1 起已修,1.20.4 真机复验);「3 个二维码」=旧实现重显+刷新叠加(1.7.5.6 手动=全新码);踢人 disconnect 1.20.4/1.21 映射正确(class_8609.method_52396),1.20.1 经映射表补丁修复(class_3244.method_14367 真机确认) |
| 图片包 | wl1「蔚蓝档案」41 张 / 86,655,491 字节(本轮未动) |
| 备份 | ✅ 单包口径:SUZHEWWO(2026.11.07-4).tar.gz(v4 全量);/SUZHE014/ 只留这一个包 |
"""

SESSION = """# 会话上下文(SUZHE014 / KERONGPenguin)

> **维护规则(长期有效)**:
> 1. 每进行一个【大步骤】就更新一次本文件,写上准确日期时间(北京时间,精确到分)。
> 2. 完成一整个任务后,再更新一次(稍微简略写一点,做总结)。
> 3. 本文件由当轮 AI 会话负责维护;新会话动手之前必须先读完知识库所有内容(见 AI交接文档.md)。
> 4. 每轮会话结束删除更早的会话小节,只保留最新一轮(2026-11-13 用户指令:知识库只保留最新的版本;历史会话记录见各轮 git 提交信息)。

---

## 会话记录

### 2026-11-13 21:50 起:1.7.5.6 Fa 修复轮(知识库精简 + Fa 1.20.1 支持 + hbkrg qr 全新码,已全部完成上线)

用户指令(会话续接,四项):
① 知识库只保留最新的版本(先做);
② 修复 Fa 发送 hbkrg qr 后直接输出 3 个二维码的问题;
③ Fa 能不能支持 1.20.1 版本;
④ 附用户日志(1.20.4+1.7.4.1 测试版执行 hbkrg qr 崩溃 NoSuchMethodError class_2561.literal)定性。

**步骤记录(北京时间 2026-11-13)**:
- [21:50-22:10] 会话续接核查:主线 E/F/G 已完成上线(远端 main=fff04da);拉最新知识库三件套;知识库精简(三件套只留最新版+删 6 个 .bak,1196→422 行)上传 md5 往返一致。
- [22:10-23:20] 问题定性:1.7.4.1 崩溃=旧打包方法名漏 remap(1.7.5.5 jar 字节码 method_43470 已正确;1.20.4 真机两场景复验 qr 正常);「3 个二维码」=旧实现手动重显+旧会话过期刷新叠加;1.20.1 静态核对(84 引用)发现 performPrefixedCommand 签名漂移(int→void)与 disconnect 继承错位→必须独立重映射。
- [23:20-23:45] 代码:QrLoginManager 手动分支取消旧会话出全新码;FabricConsoleExecutor 改 brigadier dispatcher.execute;版本号六处 1.7.5.6。
- [23:45-00:00] 1.20.1 打包链:build_fabric_1201_map.py(merged-1201.tiny+继承错位补丁;首轮补丁切断 class_3244 节被门禁 C3 抓出修正为节末插入);材料传服务器 /root/fabric-deps/1201/;package_jar_fabric.py 参数化 --mc。
- [00:00-00:05] 构建 clean 四模块(2m33s)+ 四 JAR 打包 + 门禁 verify_jar_1756 59/59 全绿。
- [00:05-00:10] 三环境真机:fa1204(1.20.4)qr×2 全新码语义;fa1201(1.20.1,Loader 0.16.14+fabric-api 0.92.12)加载/自动码/手动 qr/help 全过+运行时 method_14367/method_9235 javap 确认;fa121(1.21)加载+qr 正常。
- [00:10] 部署:四 JAR+官网三件(md5 往返一致+外网复核全过);git push main=fa88c0e;源码包 9.03MB/825 文件传 penguin-source/。

**任务完成总结(2026-11-14 00:15)**:
四项全部闭环上线。①知识库精简完成(只留最新版本口径三处同步+删旧 bak)。②「3 个二维码」修复:手动 hbkrg qr=取消旧会话出全新码(三端同步,真机验证第二次输出「已取消此前的扫码会话」+全新 task_id)。③Fa 1.20.1 支持:独立包 KERONGPenguin_Fabric-1.20.1-1.7.5.6.jar(1.20.1 映射+补丁重映射,fabric.mod.json 精确锁定 1.20.1;真机验证全过;Loader 需 ≥0.16.10 或更高,fabric-api 0.92.x+1.20.1)。④用户日志定性:1.7.4.1 旧打包 bug,1.7.5.1 起已修,升级即解决(version.json 第④条已提示)。验证:门禁 59/59+三环境真机+外网复核全绿。md5:Spigot b7d2dbcb/NeoForge 8721c7ba/Fabric-1.20.4 d9fdb397/Fabric-1.20.1 cc1d82c8/源码包 da702156。开发注意存档:知识库唯一位置 /SUZHE014/知识库/(只留最新版);1.20.1 打包链材料在服务器 /root/fabric-deps/1201/;tiny-remapper 按编译期 classpath 继承链解析方法定义类,跨版本映射表须按「解析到的定义类」补行且不能切断原节;fabric-api 0.92.12+1.20.1 要求 Loader≥0.16.10。
"""


def main():
    if not PASS:
        raise SystemExit("需要 PENGUIN_SSH_PASS")
    # 更新日志.md:头部 1-7 行(口径行)+ 状态表 + 本轮节
    with open(f"{BASE}/更新日志.md", encoding="utf-8") as f:
        lines = f.read().splitlines(keepends=True)
    head = lines[:7]  # 头部口径区(1-7 行)
    new_log = "".join(head) + "\n" + LOG_TABLE + "\n" + LOG_TAIL
    # 会话上下文.md:整体替换
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, "root", PASS, timeout=30)
    try:
        sftp = c.open_sftp()
        for name, content in (("更新日志.md", new_log), ("会话上下文.md", SESSION)):
            data = content.encode("utf-8")
            with sftp.open(f"/SUZHE014/知识库/{name}", "w") as f:
                f.write(data)
            with sftp.open(f"/SUZHE014/知识库/{name}", "r") as f:
                back = f.read()
            print(f"[{'SAME' if back == data else 'DIFF!'}] {name} {len(data)} bytes")
        _, out, _ = c.exec_command("wc -l '/SUZHE014/知识库/'*.md && ls '/SUZHE014/知识库/'", timeout=30)
        print(out.read().decode())
    finally:
        c.close()
    # 本地留档
    with open(f"{BASE}/更新日志.md", "w", encoding="utf-8") as f:
        f.write(new_log)
    with open(f"{BASE}/会话上下文.md", "w", encoding="utf-8") as f:
        f.write(SESSION)
    return 0


if __name__ == "__main__":
    sys.exit(main())
