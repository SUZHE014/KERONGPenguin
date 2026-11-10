#!/usr/bin/env python3
"""kb 更新：1.7.5 覆盖第三轮 + 补记 1.7.4/1.7.4.1/1.7.5 知识库欠账（kb 停在 1.7.3.1）。"""

PATH = "/tmp/AI-KNOWLEDGE-server.txt"
with open(PATH, encoding="utf-8") as f:
    text = f.read()

# ---- 1. 头部：新「最后更新」行置顶（原 1.7.3.1 行保留在下） ----
new_header = (
    "> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-10（**v1.7.5 覆盖第三轮**：① 回调按钮"
    "「QQ 显示操作成功但机器人没反应」真根因修复——合成事件 metadata 只写 group_openid 而 SDK "
    "getGroupOpenId() 实际读 group_id，取出 null 被群白名单拦截命令从未执行（解除黑名单走独立流程"
    "所以幸存），现双字段齐写；② 命令回复改被动通道——新增 RecentGroupMessages 登记群最近真实消息 "
    "id（TTL 4 分钟）复用为 msg_id，msg_seq 经 ReplySeqGuard 错开防 40054005 拒收，取不到自动回退"
    "主动消息；③ 菜单按钮按需补参分流——绑定/发信息/执行命令/执行/管理员执行五命令用指令按钮"
    "（actionType=2 点击复制进输入栏不设 enter），其余无参命令保持回调点击即执行；④ menu.md 去讲解"
    "字段只留分页标题+版本行；⑤ 解除黑名单字段顺序修正「发送 /解除黑名单 @对应的人 解除；也可以"
    "点击下方对应的按钮解除」；⑥ 全量转发改总开关——features.full-amount 对全部配置群统一生效，"
    "/全量 切总配置并写回 config.yml（saveConfig 注释保留），群级独立开关（command-state.ini "
    "[full-forwarding] 段）整体删除，总开关关闭时 /菜单 按钮与 QQ 快捷面板的全量按钮同步隐藏；"
    "config-version 42→43；门禁 verify_jar_175o3 72 项 + 冒烟 smoke175o3 41 项全过；git push "
    "main=45cfbcd、双 JAR+源码包+官网三件已部署（Spigot md5 90c7bdea / NeoForge 8373751f / 源码包 "
    "9c9ec63a）；**知识库欠账补记：1.7.4 / 1.7.4.1 / 1.7.5 三版期间本知识库停在 1.7.3.1 未随轮更新，"
    "三版要点已补入 §12 归档，接手时以 §12 + git log 为准**）。"
)
anchor = "> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-07（**v1.7.3.1 第四次覆盖版**"
assert anchor in text, "头部锚点未找到"
text = text.replace(anchor, new_header + "\n" + anchor, 1)

# ---- 2. §0 版本状态段替换 ----
old_ver = text[text.index("**版本规则**：插件版与模组版同步发号。当前版本 **1.7.3.1"):text.index("**git push 状态**")]
new_ver = (
    "**版本规则**：插件版与模组版同步发号。当前版本 **1.7.5（2026-11-10，覆盖第三轮同版本号直接覆盖）**"
    "（1.7.5 链：① /菜单 按钮菜单替换 /帮助——无参命令回调按钮（actionType=1 带 krg:cmd: 前缀）点击即"
    "执行、带参命令（绑定/发信息/执行命令/执行/管理员执行）指令按钮（actionType=2）点击复制进输入栏，"
    "一行一个分页展示；② 互动回应 PUT 直发修复「第三方连接失败」（SDK 代理把声明 PUT 的回应发成无 body "
    "GET 被 405 拒绝不报错——InteractionAcker 绕开直发 + InteractionFastPath 帧层快速路径，翻页前缀 "
    "krg:menu:page: 同入快速路径）；③ 回调按钮无反应真根因修复（group_id 缺失被群白名单拦截）+ 命令回复"
    "改被动通道（RecentGroupMessages 复用本群最近真实消息 id）；④ AI 命令合并 /AI管理（状态+三按钮）；"
    "⑤ /黑名单 与 /解除黑名单 统一按钮清单（命令在前）；⑥ 更新检查修复（NF MOD_VERSION 漏升 + "
    "version.json 只含当前版本条目）；⑦ 全量转发改总开关（群级开关删除、面板联动隐藏）；官网与注释清除"
    "「覆盖版」与版本新增标注、Fa 版官网卡片补 MC/Loader/API 版本；详见 §12）。前版 **1.7.4.1"
    "（2026-11-09）**（@其他用户+指令不误触发 / 服务器开关消息重做：去表情默认文案 + 180 秒重启判定窗口 "
    "restart-window（1.7.4 七轮曾删后回归为可配判定）+ 重启完毕文案 o‿∩ + start 段 + 双端 getter 接线"
    "修复）。前版 **1.7.4（2026-11-08~09，七轮覆盖链）**（QQ 扫码登录 / 多玩家绑定 qq-bind."
    "max-players-per-qq / 查信息反查 QQ / 多服务器集群 bot.cluster / 服务器开关消息 server-notify 首入 / "
    "群自动绑定 / 玩家死亡播报 player-events.death / 指令改名：查询Open ID→查信息、旧查信息→玩家名片 / "
    "多玩家选择与解除黑名单按钮化 / HuHoBot 口径清理）。config-version **43**（1.7.5 链 40→41→42→43："
    "commands.帮助→菜单与 AI 两键→AI管理 迁移、菜单/AI管理 模板新键、注释刷新、全量总开关语义变更无新增"
    "键）。**fa 版（Fabric）停在 1.7.4.1 未随 1.7.5 更新**（官网 Fabric 卡片已补 MC 1.20.4 / Loader / "
    "API 版本信息；fa 版不推 git、单独源码包+本体在服务器 downloads/，构建流程不在本仓库）。\n\n"
)
text = text.replace(old_ver, new_ver, 1)

# ---- 3. §0 git push 状态段替换 ----
old_push = text[text.index("**git push 状态**"):text.index("## 1. 环境与位置")]
new_push = (
    "**git push 状态**：✅ 已完成（2026-11-10 v1.7.5 覆盖第三轮实测，API 复核远端 main = `45cfbcd`）——"
    "**ghfast.top 代理 + 经典令牌 ghp_ 带鉴权 push 可用**（fine-grained PAT github_pat_ 前缀对该仓库 "
    "403 Permission denied）；api.github.com 直连可复核；github.com 直连 push 超时不可用。令牌经 "
    "/tmp/.ghaskpass（umask 077）+ GIT_ASKPASS 读取，用完即删。服务器中继备用：`git -C /root/gitrelay/"
    "KERONGPenguin.git fetch https://ghfast.top/https://github.com/SUZHE014/KERONGPenguin.git +main:"
    "relay-main` → bundle 拉回本地再 fetch。**沙盒环境（2026-11-10 第三次重建要点）**：JDK21 与 Gradle "
    "缓存预置在 `/home/z/my-project/tooling/`（jdk21 + gradle-home——**必须 export "
    "GRADLE_USER_HOME=/home/z/my-project/tooling/gradle-home**，默认 ~/.gradle 会重新下载发行版且 DNS "
    "间歇失败必超时）；NF libs 经 `python3 scripts/build_neoforge_libs.py` 重建（BMCLAPI 镜像，DNS 间歇"
    "失败直接重跑脚本断点续传即可，全部原料约 4 轮下载完，ART 反映射约 3 分钟需前台跑完）；base JAR（打包"
    "第三方依赖用）在 `/home/z/my-project/penguin-work-base/KERONGPenguin_Spigot-1.7.5.jar`（= 线上"
    "当前版）；后台 nohup 进程在 bash 会话间隙会被沙箱回收，长任务（gradle / ART）一律前台 timeout 包住"
    "跑；沙盒 DNS 对 piston-meta/bmclapi 等偶发 Name or service not known，脚本内建重试，外置 curl 需"
    "手动重试。**知识库新规（1.7.4.1 轮确立）**：AI-KNOWLEDGE 不进 git（.gitignore 已收录），只存服务器"
    "两处（/wwwroot/penguin/AI-KNOWLEDGE.txt 官网自包含版 + /SUZHE014/AI-KNOWLEDGE.txt 交接副本），每个"
    "大步骤（发版/覆盖轮）后更新；git 里只留源码。\n\n"
)
text = text.replace(old_push, new_push, 1)

# ---- 4. §12 归档：三版条目置顶 ----
old_12_head = "## 12. 历史版本归档（要点版，详细日志见 git log；**1.7.0 前版本官网条目与下载本体 2026-11-04 删除归档，其中互转版同日经用户指令恢复上线（见 §10），其余仍归档在服务器 /root/penguin-archive-downloads/，本节仅知识留档**）\n"
assert old_12_head in text, "§12 锚点未找到"
new_entries = old_12_head + (
    "\n- **1.7.5（2026-11-10，覆盖三轮链，同版本号直接覆盖）**：一轮 /菜单 按钮菜单（替换 /帮助）+ 互动"
    "回应 PUT 直发修复「第三方连接失败」（InteractionAcker 绕开 SDK 代理 + InteractionFastPath 帧层快速"
    "路径先回应后操作）+ AI 命令合并 /AI管理（三按钮竖排）+ 黑名单/解除黑名单统一按钮清单 + 更新检查修复"
    "（NF MOD_VERSION 漏升、version.json 只含当前版本）+ 官网与注释清「覆盖版」字段和版本新增标注 + Fa 版"
    "官网卡片补 MC/Loader/API 版本；二轮翻页按钮前缀 krg:menu:page: 加入帧层快速路径（防回应被命令处理"
    "拖超 3 秒时限）；三轮回调按钮无反应真根因修复（group_id）+ 回复被动通道（RecentGroupMessages）+ "
    "按钮按需补参分流（INPUT_COMMANDS 五命令 actionType=2）+ menu.md 去讲解留版本行 + unblacklist-list "
    "命令在前 + 全量转发总开关（群级 [full-forwarding] 段删除、/全量 写回 saveConfig、面板+键盘按钮联动"
    "隐藏）；config-version 40→41→42→43；门禁 verify_jar_175o3 72 项 + 冒烟 smoke175o3 41 项；提交 "
    "45cfbcd。\n"
    "- **1.7.4.1（2026-11-09，二轮）**：① @QQ用户 + 指令（如「@张三 /查在线」）不再误触发机器人——按 @"
    " 对象分流：@机器人（三通道判定）行为不变，只 @ 其他人保留 <@ 占位> 片段不剥使命令匹配自然失败，纯"
    "数字自动绑定与多玩家选择确认同样不命中；② 服务器开关消息重做——默认文案去表情（正常关闭 [状态] "
    "服务器已正常关闭 / 意外关闭 [状态] 服务器....）+ 180 秒重启判定窗口（server-notify.restart-window "
    "默认 180 可配 0=不判定：窗口内再开机判定重启发 [状态] 服务器重启完毕，已正常运行(o‿∩)，超窗按普通"
    "开机走 start 段新键发开启文案，首次安装不发送）+ 修复 1.7.4 起双端主类未接线 server-notify getter "
    "的遗留问题（配置改文案此前不生效）；config-version 39→40；门禁 verify_jar_1741 58 项 + 1741o2 74 "
    "项 + 冒烟 55 项；知识库退出 git 改存服务器。\n"
    "- **1.7.4（2026-11-08~09，七轮覆盖链）**：QQ 扫码登录（未配置凭据 30 秒固定延时打印二维码、过期"
    "自动刷新上限 3 次、/hbkrg qr 手动发起、手动扫码成功换凭据重连）；QQ 多玩家绑定 qq-bind."
    "max-players-per-qq（默认 1，调大先弹玩家选择按钮）；查信息反查（/查信息 玩家名 反查 QQ 与名下全部"
    "绑定，QQ 号行全员可见）；多服务器集群 bot.cluster（群里命令先弹选择服务器按钮、/切换服务器、未配置"
    "零变化）；服务器开关消息 server-notify 首入（关机/重启/意外关闭，插件重载不误发）；群自动绑定"
    "（bot.groups 留空自动入白名单）；玩家死亡播报 player-events.death（默认关，{message} 中文死亡描述 / "
    "{killer} 击杀者）；指令改名（查询Open ID→查信息、旧查信息统计卡→玩家名片，旧名兜底下线）；多玩家"
    "选择与解除黑名单按钮交互化；副标题框默认灰度 106 收敛；删旧命令 /金币榜；开关消息默认文案七轮收敛"
    "（[状态] 前缀+颜文字 → 颜文字修正 ༽◺_◿༼ → restart-window 删除改 previousStopTime>0 → 1.7.4.1 又以"
    "判定窗口形式回归可配）；二维码启动显示改固定延时 30 秒不挑核心（ServerLoadEvent 待发标记机制废弃）；"
    "config-version 36→37→38→39；门禁 verify_jar_174 系列至 o7 108 项 + 冒烟至 47 项。\n"
)
text = text.replace(old_12_head, new_entries, 1)

with open(PATH, "w", encoding="utf-8") as f:
    f.write(text)
print("kb 更新完成，行数:", text.count("\n") + 1)
