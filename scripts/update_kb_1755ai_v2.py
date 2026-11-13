#!/usr/bin/env python3
"""1.7.5.5 AI 覆盖轮知识库更新（v2：新体系 /SUZHE014/知识库/ 三件套）。

背景：2026-11-13 21:37 用户删除旧两处 AI-KNOWLEDGE.txt（留言「以后上下文不准
在这个目录」）——知识库唯一位置为 /SUZHE014/知识库/（AI交接文档.md / 会话
上下文.md / 更新日志.md）。本轮补记缺失的 1.7.5.5 Fa 阶段条目 + 新增 AI
覆盖轮条目，更新状态表与 push 状态，追加会话上下文；另传源码包。
"""
import hashlib
import os
import sys

import paramiko

HOST = os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248")
USER = os.environ.get("PENGUIN_SSH_USER", "root")
PASS = os.environ.get("PENGUIN_SSH_PASS", "")

KB_DIR = "/SUZHE014/知识库"
SOURCE_ZIP = ("/home/z/my-project/penguin-git/build/dist/"
              "KERONGPenguin_v1.7.5.5_source.zip")

STATE_TABLE = """## 当前版本状态（更新于 2026-11-13 21:44）

| 项 | 状态 |
|---|---|
| 当前版本 | **1.7.5.5**（同号两波：凌晨 Fa 阶段二维码编码自适应/Fabric 元数据上限/官网口径修正 + 晚间 AI 覆盖轮 AI 艾特/AI 回复引用开关/官网更新日志简化；config-version 45 → 46 新增 ai.at-enabled 与 ai.reply-quote 两键，只加新键默认值） |
| 三端产物 | KERONGPenguin_Spigot-1.7.5.5.jar（md5 2c821343…）/ KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar（62b4af7f…）/ KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar（9b28e53a…）（/wwwroot/penguin/downloads/，md5 往返一致，外网 200 验证过；线上为 AI 覆盖轮产物，覆盖凌晨 Fa 阶段版） |
| config-version | 46（新键 ai.at-enabled / ai.reply-quote，默认均 true，升级自动写入无需手动改配置） |
| git push | ✅ GitHub main = **66105f5**（ghfast.top 代理 + classic ghp_ GIT_ASKPASS 推送 9f3faa1 → 2ace545 → 66105f5 三连；**连续 push 时代理缓存旧 ref 会报 cannot lock ref，重试一次即过**；api.github.com 直连复核过） |
| 官网 | version.json（1.7.5.5 AI 轮四条）/ index.html（**更新日志超级简化**：九版各 1-2 条短句 + 主框 + 历史表）/ tutorial.html（AI 段补 ai.at-enabled / ai.reply-quote 两个 cfg-item）上线；源码包 KERONGPenguin_v1.7.5.5_source.zip（9.0MB / 822 文件，HEAD 66105f5）传 /wwwroot/penguin-source/ |
| MC 服务端 | ⚠️ 不在 156.233.228.248 上；用户在实际服务器替换 JAR 并重启后生效（同号 1.7.5.5 从凌晨 Fa 版升到 AI 覆盖轮版需替换重启；AI 两键自动写入） |
| fa 版本 | Fabric 1.7.5.5 随主线同号两波同步（二维码编码自适应 + AI 艾特/引用全随行；fabric.mod.json 1.7.5.5） |
| Fa 遗留 | ⚠️ 用户称 Fa 1.21 二维码输出「签名不匹配」崩溃——凌晨轮七版本全面复现失败（正常输出），晚间轮未再复现；须专项定位（需用户 crash-report/latest.log 或真机堆栈） |
| 图片包 | wl1「蔚蓝档案」41 张 / 86,655,491 字节（本轮未动） |
| 备份 | ✅ 单包口径：SUZHEWWO（2026.11.07-4）.tar.gz（v4 全量）；/SUZHE014/ 只留这一个包 |

"""

NEW_ROUND = """### 2026-11-13（凌晨 Fa 阶段 + 21 时 AI 覆盖轮，同号 1.7.5.5 两波）：Fa——二维码编码自适应 / Fabric 元数据上限 / 官网口径修正；AI 覆盖轮——AI 艾特 / AI 回复引用开关 / 官网更新日志超级简化

**Fa 阶段（00:30-09:10，commit 132e410）**：
- 官网模组页版本清单真机核查：Fabric 1.20.4/1.20.5/1.20.6/1.21/1.21.4/1.21.6/1.21.11 七版本 + NeoForge 1.20.4/1.21.11 两端全部真实服务器启动验证 PASS（NeoForge 1.20.6/1.21/1.21.4 为用户已测）；**Fabric Loader 实际下限 0.15.1**（fabric-api 0.97.3+1.20.4 起要求，实测 0.15.0 不可用）；fabric.mod.json minecraft 声明补上限 >=1.20.4 <1.22（26.x 新版本线明确提示不兼容）。
- 修「中文 Windows 控制台扫码二维码不可扫」：JDK GBK 表含 ▄█ 但不含 ▀（U+2580），真机 GBK 输出「█▄ 幸存 + ▀ 变 ?」混合损坏不可扫（实测不崩溃仅损坏）→ QrCode 新增 encodeCjk（■ U+25A0 + U+3000 全角，GBK 可编码）与 encodePlain（##/空格对纯 ASCII）两级回退渲染器，QrLoginManager.renderQrForConsole 按 System.out 实际 charset 三级选择（UTF-8 原版半块零变化 / GBK 全角方块 / 极端纯 ASCII），三端同步受益。
- 「Fa 1.21+ 二维码输出崩溃」全面复现失败：7 版本 × 管道/伪终端 TTY/GBK 三控制台形态均正常无异常；代码事实否定「新版渲染/图像 API」怀疑（QR 路径纯 Kotlin 编码 + slf4j→log4j Console→System.out，不经过任何 MC 渲染/图像 API）。
- 验证：门禁 verify_jar_1755 53 项 + 冒烟 smoke_1755 24 项 + 外网 verify_online_1755 26 项全绿；md5 Spigot cc2c496f / NeoForge 0b787f2f / Fabric 2e2dc9bc（后被 AI 覆盖轮覆盖）；源码包 9.2MB/819 文件。

**AI 覆盖轮（20:29-21:44，commit 9f3faa1 + 2ace545 + 66105f5）**：
- [20:29-20:50] 会话续接（用户指令三项 + 版本同号覆盖 1.7.5.5）：沙箱核查（上会话已建 penguin-git/tooling/上下文.md，主线 E 支撑层已动工）/ 0.6 服务器核查完成（线上三 JAR md5 与知识库一致）/ 线上官网四件拉回（与仓库 md5 全 SAME——仓库版即底稿）。
- [20:50-21:15] 代码实施：**AiAt 新类**（parseIncomingMentions 入向：mentions 昵称还原 @昵称，bot=true/未知剥掉，<@!id> 频道形态同解析；renderOutgoing 出向：[at:群昵称] 标记渲染为 QQ 官方 Markdown 艾特 <@openid>，反查两级=本条 mentions → OpenIdDirectory.reverseDirectory，未命中降级纯文本不丢字；systemPromptSuffix 附注措辞克制防刷屏）+ GroupMessageHandler 双向接线（开关关走旧路径零变化）+ AiChat system prompt 拼接 + OpenIdDirectory 反向目录（双键原样+小写，磁盘回填同步）+ QqBindManager 两 getter + GroupMsgSender.sendReply 加 quote 参数（false 走 replyMarkdown 普通被动回复不带 message_reference）+ ConfigManager 46 + DEFAULT_VALUES 两键 + 三端 config.yml 两键+注释。
- [21:15-21:25] 官网三件：index.html 更新日志超级简化（九版各 1-2 条短句，标签配平校验过）；version.json 口径合并 AI 轮；tutorial.html AI 段补两个 cfg-item；README 摘要+详史合并。
- [21:25-21:38] 构建+验证（两轮）：首轮门禁 12 FAIL 抓出**资源 config-version 45→46 漏升**（三端补升，历史同款坑）+ 门禁类路径写错 + D1 阈值口径；首轮冒烟 3 FAIL 抓出 AiAt 正则 {1,64} 漏空标记 [at:]（改 {0,64}）+ 冒烟预期两处笔误；修后 clean 全量重建 + 三端重打包（Fabric 删重映射缓存）→ 门禁 verify_jar_1755ai **106/106** + 冒烟 smoke_1755ai **26/26** 全绿（冒烟用 JDK 动态代理 GroupMessageEvent 接口 + RawMessage/User 流式 setter 组装真 mentions 数组，不经桩全链路）。
- [21:38-21:44] 部署上线：三 JAR 同号覆盖 + 官网三件（chown www:www + chmod 644，md5 往返 6/6）；外网 verify_online_1755ai **23/23** 全绿；git push main=66105f5（首轮 push 后代理缓存旧 ref 报 cannot lock ref，重试即过；api.github.com 复核）；源码包 9.0MB/822 文件（make_source_zip.py 排除清单已固化入库——git ls-files 全量 1918 文件 384MB 不可用）。
- **待办（Fa 阶段遗留）**：Fa 1.21 二维码「签名不匹配」崩溃须专项复现定位（需用户 crash-report/latest.log 或真机堆栈）。

"""

CTX_SECTION = """---

### 2026-11-13 20:29 起：1.7.5.5 AI 覆盖轮（AI 艾特 / AI 回复引用开关 / 官网更新日志超级简化，同号覆盖，已全部完成上线）

用户三项（版本同号覆盖 1.7.5.5，基线 132e410）：
① AI 艾特——ai.at-enabled 默认开：AI 能看到群里谁被艾特（占位还原 @昵称），AI 也可在回复输出 [at:群昵称] 自行艾特成员；
② AI 配置新增「QQ群回复是否引用消息」（ai.reply-quote 默认开；关闭时 AI 回复不引用任何消息）；
③ 官网更新日志超级简化。

**步骤记录（北京时间 2026-11-13）**：
- [20:29] 会话续接（上会话被中断，沙箱留有 penguin-git/tooling/上下文.md 与已动工的 E 支撑层）。
- [20:29-20:50] 启动流程收尾：0.6 服务器核查（线上三 JAR md5 与知识库一致 cc2c496f/0b787f2f/2e2dc9bc）；线上官网四件拉回与仓库全 SAME。
- [20:50-21:15] 代码实施（详见更新日志.md 本轮节）：AiAt 新类双向（入向 parseIncomingMentions / 出向 renderOutgoing + systemPromptSuffix）+ Handler/AiChat/OpenIdDirectory(reverseDirectory 双键)/QqBindManager/GroupMsgSender(quote 参数)/ConfigManager(46) 接线 + 三端 config.yml 两键。
- [21:15-21:25] 官网三件（index 更新日志超级简化 / version.json 合并口径 / tutorial 两个 cfg-item）+ README。
- [21:25-21:38] 两轮构建验证：首轮门禁抓出资源 config-version 漏升 46 + 首轮冒烟抓出 AiAt 正则漏空标记（{1,64}→{0,64}）；修后门禁 106/106 + 冒烟 26/26 全绿（JDK 动态代理事件 + 真 mentions 数组全链路，不经桩）。
- [21:38-21:44] 部署三 JAR 同号覆盖 + 官网三件 md5 往返 6/6；外网复核 23/23；git push main=66105f5（9f3faa1 → 2ace545 → 66105f5 三连，代理缓存旧 ref 重试即过）；源码包 9.0MB/822 文件。
- [21:44] 知识库更新（本节 + 更新日志.md 补记 Fa 阶段与 AI 覆盖轮 + 状态表）。

**任务完成总结（2026-11-13 21:44）**：
用户三项全部完成上线，同号 1.7.5.5 覆盖（config-version 45→46 新两键）。① AI 艾特默认开（入向占位还原 @昵称送 AI；出向 [at:昵称]→官方艾特 <@openid>，两级反查 mentions→OpenIdDirectory 反向目录，未命中降级纯文本；关时旧行为零变化）② AI 回复引用开关默认开（关时普通被动回复不引用任何消息）③ 官网更新日志九版各 1-2 条短句。验证：门禁 106 + 冒烟 26 + 外网 23 全绿。md5：Spigot 2c821343 / NeoForge 62b4af7f / Fabric 9b28e53a。**Fa 阶段遗留待办**：Fa 1.21 二维码「签名不匹配」崩溃须专项复现定位（需用户提供 crash-report/latest.log 或真机堆栈）。开发注意存档：知识库唯一位置 /SUZHE014/知识库/（2026-11-13 21:37 用户删旧两处 txt 并留言「以后上下文不准在这个目录」——官网目录绝不再放任何上下文/交接文档）；AiAt 正则限长须含空标记形态；冒烟可用 JDK 动态代理 SDK 接口事件 + 流式 setter 组装真实数据结构。

"""

SYNC_NOTE = """### 2026-11-13（AI 覆盖轮同步）
- ✅ 知识库位置最终定版：2026-11-13 21:37 用户删除 /wwwroot/penguin/AI-KNOWLEDGE.txt 与 /SUZHE014/AI-KNOWLEDGE.txt（留言「已由本人修改，以后上下文不准在这个目录」）——**知识库唯一位置 = /SUZHE014/知识库/ 三件套，官网目录绝不再放任何上下文/交接文档**（本文件「存放位置」条目口径自此收紧为唯一）。
- ✅ 1.7.5.5 AI 覆盖轮上线：AI 艾特 + AI 回复引用开关 + 官网更新日志超级简化（详见 更新日志.md）；main=66105f5。

"""


def md5_file(path):
    with open(path, "rb") as fp:
        return hashlib.md5(fp.read()).hexdigest()


def main():
    if not PASS:
        raise SystemExit("需要 PENGUIN_SSH_PASS")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, USER, PASS, timeout=30)
    try:
        sftp = c.open_sftp()

        # ---------- 更新日志.md ----------
        path = f"{KB_DIR}/更新日志.md"
        text = sftp.open(path, "rb").read().decode("utf-8")
        if "AI 覆盖轮" not in text:
            # 状态表整体替换（## 当前版本状态 … 到下一个 ## 标题前）
            i = text.index("## 当前版本状态")
            j = text.index("## 最近四轮")
            text = text[:i] + STATE_TABLE + "\n" + text[j:]
            # 最近四轮标题更新
            old_h = ("## 最近四轮详细更新日志（1.7.5.4 / 1.7.5.3 / 1.7.5.2 / 1.7.5.1；"
                     "更早的 1.7.5 及以前段落移入下方历史区，全量历史见 git 提交历史）")
            new_h = ("## 最近四轮详细更新日志（1.7.5.5（Fa+AI 覆盖两波）/ 1.7.5.4 / 1.7.5.3 / 1.7.5.2；"
                     "更早段落移入下方历史区，全量历史见 git 提交历史）")
            assert old_h in text, "最近四轮标题未找到"
            text = text.replace(old_h, new_h)
            # 插入 1.7.5.5 两波新节（紧跟标题之后）
            text = text.replace(new_h + "\n\n", new_h + "\n\n" + NEW_ROUND, 1)
            # 摘除 1.7.5.1 节（移入历史区顶部）
            a = text.index("### 2026-11-10~11：1.7.5.1（已下架 2026-11-12，知识留档）")
            b = text.index("## 服务器端与工具维护记录")
            moved = text[a:b]
            text = text[:a] + text[b:]
            hist_marker = "## 插件版本详细历史（只保留最近 4 轮：1.7.4 覆盖版 / 1.7.4 初版 / 1.7.3.1 三次覆盖 / 二次覆盖；更早见文件头指针）"
            assert hist_marker in text, "历史区标题未找到"
            text = text.replace(hist_marker + "\n",
                                hist_marker + "；1.7.5.1 已下架条目自最近区移入）\n" + moved, 1)
            with sftp.open(path, "wb") as f:
                f.write(text.encode("utf-8"))
            back = sftp.open(path, "rb").read().decode("utf-8")
            assert back == text and "AI 覆盖轮" in back
            print(f"[OK] 更新日志.md 已更新（{len(text)} 字符）")

        # ---------- 会话上下文.md ----------
        path = f"{KB_DIR}/会话上下文.md"
        text = sftp.open(path, "rb").read().decode("utf-8")
        if "1.7.5.5 AI 覆盖轮" not in text:
            text = text.rstrip("\n") + "\n\n" + CTX_SECTION
            with sftp.open(path, "wb") as f:
                f.write(text.encode("utf-8"))
            back = sftp.open(path, "rb").read().decode("utf-8")
            assert back == text
            print(f"[OK] 会话上下文.md 已追加（{len(text)} 字符）")

        # ---------- AI交接文档.md ----------
        path = f"{KB_DIR}/AI交接文档.md"
        text = sftp.open(path, "rb").read().decode("utf-8")
        if "知识库位置最终定版" not in text:
            anchor = "# 第二部分：完整操作手册（原 AI-KNOWLEDGE.md 2026-11-06 版全文并入）"
            assert anchor in text, "第二部分锚点未找到"
            text = text.replace(anchor, SYNC_NOTE + "\n" + anchor, 1)
            with sftp.open(path, "wb") as f:
                f.write(text.encode("utf-8"))
            back = sftp.open(path, "rb").read().decode("utf-8")
            assert back == text
            print(f"[OK] AI交接文档.md 已补同步记录（{len(text)} 字符）")

        # ---------- 源码包 ----------
        sftp.put(SOURCE_ZIP, "/wwwroot/penguin-source/KERONGPenguin_v1.7.5.5_source.zip")
        _, out, _ = c.exec_command(
            "chown www:www /wwwroot/penguin-source/KERONGPenguin_v1.7.5.5_source.zip && "
            "chmod 644 /wwwroot/penguin-source/KERONGPenguin_v1.7.5.5_source.zip && "
            "md5sum /wwwroot/penguin-source/KERONGPenguin_v1.7.5.5_source.zip && "
            "chown root:root '/SUZHE014/知识库/'*.md 2>/dev/null; ls -la '/SUZHE014/知识库/'")
        print(out.read().decode())
        remote_md5 = None
        for line in out.read().decode().splitlines() if False else []:
            pass
        _, out2, _ = c.exec_command("md5sum /wwwroot/penguin-source/KERONGPenguin_v1.7.5.5_source.zip")
        remote_md5 = out2.read().decode().split()[0]
        local_md5 = md5_file(SOURCE_ZIP)
        ok = remote_md5 == local_md5
        print(f"[{'OK' if ok else 'FAIL'}] 源码包 md5 {remote_md5}")
        if not ok:
            sys.exit(1)
    finally:
        c.close()
    print("知识库三件套（新体系）+ 源码包全部更新完成")


if __name__ == "__main__":
    main()
