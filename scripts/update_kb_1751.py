#!/usr/bin/env python3
"""kb 更新：v1.7.5.1（被动回复轮转 / 按钮管理员权限 / 模板升级放宽 / 互动回应
超时收紧 / 全量面板刷新 / fa 入库主线与官网口径变更 / 1.7.3 前归档下线）。"""

PATH = "/tmp/AI-KNOWLEDGE.txt"
with open(PATH, encoding="utf-8") as f:
    text = f.read()

# ---- 1. 头部：新「最后更新」行置顶 ----
new_header = (
    "> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-10（**v1.7.5.1**：① 40034128"
    "「被动回复时间或者次数超过限制」修复——同一条消息被动回复约 5 次上限，连点按钮全引用同一条"
    "消息即触顶；RecentGroupMessages 改**环形缓冲 16 条 + round-robin 轮转**（latestId→nextId，"
    "java.util.ArrayDeque 无索引访问编译坑→ArrayList），QClient.replyMarkdown 平台拒收/异常时"
    "**自动转主动消息通道重发**（resendActively）；② **按钮管理员权限修复**——群消息 author 块带"
    " member_role（owner/admin/member）而按钮回调（INTERACTION_CREATE）只带 group_openid + "
    "group_member_openid 不带角色，admin.mode=qq 下群主点管理按钮也判无权限；新增 **MemberRoleCache**"
    "（群+成员 composite key，30 分钟保留、随消息即时刷新、惰性清理），GroupMessageHandler 每条真实"
    "消息登记角色，MenuButtons.dispatchAsClicker 合成事件 author.member_role 从缓存补齐，分发日志加"
    "「角色=」字段；③ 模板升级匹配放宽——UpgradeSpec（olds 逐版 normalize 空白剥离比对 + features "
    "默认特征兜底收敛未收录中间态），自定义内容不动、幂等；④ 互动回应超时收紧——InteractionAcker "
    "connect 4s→1.5s / read 6s→2.5s + sendOnce 三态（null=网络抖动 400ms 快速重试一次），两次机会"
    "落在官方 3 秒窗口内（「第三方连接失败」概率修复）；⑤ /全量 切换后 refreshPanelsAsync 异步重同步"
    "全部配置群 QQ 快捷面板（qq-panel-refresh 守护线程，未连接静默跳过）。门禁 verify_jar_1751 "
    "**43 项** + 冒烟 smoke_1751 **34 项**全绿；git push main=`5484b6d`；双 JAR 已部署（Spigot md5 "
    "6679c015 / NeoForge c4118178 往返一致）。**官网口径变更（用户指令）**：官网删除 1.7.3 以前全部"
    "内容（下载 JAR 与更新日志一并归档 /root/penguin-archive-downloads/，页面留一行归档说明）；"
    "**源码包全部下架官网**（/wwwroot/penguin-source/ 目录删除、downloads/ 里 Source-v*.zip 一并归档，"
    "此后源码只在 GitHub）；Folia 芯片去「多线程区域化核心」描述；模组版行的（MC 1.20.4，1.20.6，"
    "1.21.x，Java 21）移到 NeoForge 芯片上；Fabric 卡片改「主系已测试版本：1.20.4，1.21，1.21.6」+"
    "「支持版本：1.20.4 及以上」两行口径。**fa 版（Fabric）新政策**：server-Fabric 模块（1.7.4.1 状态）"
    "**已移入主线 git**（commit 5484b6d，默认不进 settings.gradle 构建——构建期 fabric libs 不入库，"
    "构建方式与 1.20.1 下探可行性评估见 server-Fabric/README.md），**只有用户明确要求时才更新 fa 版**，"
    "本轮 fa 仍发布 1.7.4.1 不随主线升 1.7.5.1）。"
)
anchor = "> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-10（**v1.7.5 覆盖第三轮**"
assert anchor in text, "头部锚点未找到"
text = text.replace(anchor, new_header + "\n" + anchor, 1)

# ---- 2. §0 版本状态行 ----
old_ver_head = "**版本规则**：插件版与模组版同步发号。当前版本 **1.7.5（2026-11-10，覆盖第三轮同版本号直接覆盖）**"
assert old_ver_head in text, "版本规则锚点未找到"
new_ver_head = (
    "**版本规则**：插件版与模组版同步发号。当前版本 **1.7.5.1（2026-11-10 同日小版本）**"
    "（1.7.5.1：① 被动回复轮转——RecentGroupMessages 环形缓冲 16 条 nextId 轮转分摊 + QClient 拒收"
    "/异常自动转主动消息重发，修 40034128；② 按钮管理员权限——MemberRoleCache 缓存群成员角色补进"
    "合成事件 author.member_role（按钮回调不带角色，admin.mode=qq 判定曾全失效）；③ 模板升级放宽"
    " UpgradeSpec normalize+特征兜底；④ InteractionAcker 超时 1.5s/2.5s + 400ms 快速重试；⑤ /全量 "
    "切换后异步重同步快捷面板；config-version 保持 43 无新增键）。前版 **1.7.5（2026-11-10，覆盖第三轮同版本号直接覆盖）**"
)
text = text.replace(old_ver_head, new_ver_head, 1)

# ---- 3. §0 git push 状态：main 指向更新 ----
old_push_sha = "**git push 状态**：✅ 已完成（2026-11-10 v1.7.5 覆盖第三轮实测，API 复核远端 main = `45cfbcd`）"
assert old_push_sha in text, "push 状态锚点未找到"
new_push_sha = "**git push 状态**：✅ 已完成（2026-11-10 v1.7.5.1 实测，API 复核远端 main = `5484b6d`）"
text = text.replace(old_push_sha, new_push_sha, 1)

# ---- 4. fa 版状态行：新政策 ----
old_fa = "**fa 版（Fabric）停在 1.7.4.1 未随 1.7.5 更新**（官网 Fabric 卡片已补 MC 1.20.4 / Loader / API 版本信息；fa 版不推 git、单独源码包+本体在服务器 downloads/，构建流程不在本仓库）。"
assert old_fa in text, "fa 状态锚点未找到"
new_fa = ("**fa 版（Fabric）停在 1.7.4.1 未随主线升 1.7.5.1（1.7.5.1 轮起源码已入主线 server-Fabric，"
          "只有用户明确要求才更新 fa；官网卡片改「主系已测试版本 / 支持版本」口径，源码包已全面下架官网，"
          "本体仍在服务器 downloads/，构建期 fabric libs 在服务器 /root/fabric-deps/ + server-Fabric/README.md）**。")
text = text.replace(old_fa, new_fa, 1)

# ---- 5. base JAR 路径更新（沙盒回收后位置变化） ----
old_base = "base JAR（打包第三方依赖用）在 `/home/z/my-project/penguin-work-base/KERONGPenguin_Spigot-1.7.5.jar`（= 线上当前版）"
if old_base in text:
    new_base = ("base JAR（打包第三方依赖用）沙箱回收后从官网下载到 `/home/z/my-project/tooling/online-1.7.5.jar`"
                "（当前线上=1.7.5；1.7.5.1 上线后下次重建应下载 1.7.5.1 版，注意 server-Spigot/libs/deps.jar "
                "与 common-Bot/libs/deps.jar 同为 gitignore 不入库、沙箱回收后需重建——cp common-Bot/libs/deps.jar "
                "server-Spigot/libs/ 即可）")
    text = text.replace(old_base, new_base, 1)

with open(PATH, "w", encoding="utf-8") as f:
    f.write(text)
print("KB 更新完成：头部 + §0 版本 + push 状态 + fa 政策 + base JAR 路径")
