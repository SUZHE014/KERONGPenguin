#!/usr/bin/env python3
"""1.7.5.9 覆盖轮官网+README 修复（幂等；修首发 1759 三处缺陷 + 显示名去配置化口径）

修复项：
  1. version.json —— 首发误用对象格式且 insert(0) 跑两次产生重复条目 +
     1.7.5.8 旧字符串条目遗留（UpdateChecker 消费纯字符串数组）→ 全量重建
  2. index.html —— v1.7.5.9 更新日志块重复两份 + 历史表 1.7.5.9 四行重复两份
     → 去重并改为「显示名统一群昵称」口径（新增 ④ 官网修复条目）
  3. tutorial.html —— qq-forward-display-name 配置条目 → 固定行为说明
  4. README.md —— 补缺失的 1.7.5.9 摘要段 + 详史节改覆盖轮口径
"""
import json
import pathlib

W = pathlib.Path('/home/z/my-project/penguin-git/website')
R = pathlib.Path('/home/z/my-project/penguin-git/README.md')

# ---------- 1. version.json（全量重建，纯字符串数组） ----------
ITEMS = [
    "① 调整：QQ 群消息转发到游戏的显示名统一固定为发送者的群昵称（设置过群名片就是群名片，没设置就是他的 QQ 昵称），不再提供配置项；「/发信息」命令转发同为群昵称（配置版本 50 → 51，历史显示名配置键升级自动删除）",
    "② 修复：菜单模板替换不完整——命中任一历史版本内置默认（用户没改过）时改为删除文件重新生成本版默认，改过的保留不动",
    "③ 重做：Fabric 版 QQ 绑定策略——未绑定玩家进入服务器后限制全部互动（锁定移动 / 禁方块与生物交互 / 禁发消息与命令），聊天栏持续输出绑定码，超时踢出（qq-bind.restrict-timeout-seconds 默认 120 秒可配），群里绑定成功后自动解除全部限制；/hbkrg qbd 取码指令删除",
    "④ 修复：官网版本文件更新日志格式错乱与重复条目，已恢复纯文本条目（更新检查控制台显示异常一并修复）",
]
vj = W / 'version.json'
new_vj = json.dumps({"version": "1.7.5.9", "url": "https://kerong.xin/penguin/",
                     "changelog": ITEMS}, ensure_ascii=False, indent=2) + '\n'
cur = json.loads(vj.read_text(encoding='utf-8'))
if cur != json.loads(new_vj):
    # 幂等前置：当前必须是首发损坏态（含对象条目或重复）或已是目标态之外的异常
    assert any(isinstance(x, dict) for x in cur['changelog']) or \
        len(cur['changelog']) != len(ITEMS), 'version.json 状态非预期，人工核查'
    vj.write_text(new_vj, encoding='utf-8')
print('[ok] version.json 重建：纯字符串数组', len(ITEMS), '条')

# ---------- 2. index.html ----------
ip = W / 'index.html'
t = ip.read_text(encoding='utf-8')

OLD_BLOCK = ('<div class="version"><h3>v1.7.5.9</h3><span class="date">2026-11-14</span><ul>\n'
             '<li>新增：QQ 群消息转发到游戏的显示名可选群昵称 / QQ 用户名（chat-format.qq-forward-display-name，'
             '默认群昵称；QQ 官方接口在群消息里只返回一个昵称字段，两个选项当前显示同一个值）；'
             '「/发信息」命令转发的名字保持群昵称不变。</li>\n'
             '<li>修复：菜单模板替换不完整——命中任一历史版本内置默认（没改过）改为删除文件重新生成本版默认；改过的保留不动。</li>\n'
             '<li>重做：Fabric 版 QQ 绑定策略——未绑定玩家进入服务器后限制全部互动（锁定移动 / 禁交互 / 禁聊天与命令），'
             '聊天栏持续输出绑定码，超时踢出（qq-bind.restrict-timeout-seconds 默认 120 秒可配）；'
             '群里绑定成功自动解除全部限制；/hbkrg qbd 取码指令删除。</li>\n'
             '</ul></div>')
NEW_BLOCK = ('<div class="version"><h3>v1.7.5.9</h3><span class="date">2026-11-14</span><ul>\n'
             '<li>调整：QQ 群消息转发到游戏的显示名统一固定为发送者的群昵称（设置过群名片就是群名片，'
             '没设置就是 QQ 昵称），不再提供配置项（历史显示名配置键升级自动删除，配置版本 49 / 50 → 51）；'
             '「/发信息」命令转发同为群昵称，两条路径口径一致。</li>\n'
             '<li>修复：菜单模板替换不完整——命中任一历史版本内置默认（没改过）改为删除文件重新生成本版默认；改过的保留不动。</li>\n'
             '<li>重做：Fabric 版 QQ 绑定策略——未绑定玩家进入服务器后限制全部互动（锁定移动 / 禁交互 / 禁聊天与命令），'
             '聊天栏持续输出绑定码，超时踢出（qq-bind.restrict-timeout-seconds 默认 120 秒可配）；'
             '群里绑定成功自动解除全部限制；/hbkrg qbd 取码指令删除。</li>\n'
             '<li>修复：官网版本文件（version.json）更新日志格式错乱与重复条目，已恢复纯文本条目'
             '（此前更新检查在控制台显示的异常内容一并修复）。</li>\n'
             '</ul></div>')
n_old = t.count(OLD_BLOCK)
assert n_old == 2, f'v1.7.5.9 日志块应重复 2 份，实际 {n_old}'
assert t.count(NEW_BLOCK) == 0
t = t.replace(OLD_BLOCK + '\n' + OLD_BLOCK, NEW_BLOCK)
assert t.count(NEW_BLOCK) == 1 and t.count(OLD_BLOCK) == 0

# 历史表 1.7.5.9 四行重复两份（第二组首行带 8 空格前导）
ROWS = ('<tr><td>NeoForge 1.7.5.9</td><td>2026-11-14</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.9.jar">下载</a></td></tr>\n'
        '<tr><td>Spigot 1.7.5.9</td><td>2026-11-14</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.5.9.jar">下载</a></td></tr>\n'
        '<tr><td>Fabric 1.7.5.9（1.20.4 通用包）</td><td>2026-11-14</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.9.jar">下载</a></td></tr>\n'
        '<tr><td>Fabric 1.7.5.9（1.20.1 独立包）</td><td>2026-11-14</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.5.9.jar">下载</a></td></tr>')
double_rows = ROWS + '\n        ' + ROWS
n_rows = t.count('<tr><td>NeoForge 1.7.5.9</td>')
assert n_rows == 2, f'历史表 1.7.5.9 行组应重复 2 份，实际 {n_rows}'
t = t.replace(double_rows, ROWS)
assert t.count('<tr><td>NeoForge 1.7.5.9</td>') == 1

ip.write_text(t, encoding='utf-8')
print('[ok] index.html：日志块去重+口径更新+新增④；历史表去重')

# ---------- 3. tutorial.html ----------
tp = W / 'tutorial.html'
u = tp.read_text(encoding='utf-8')
OLD_CFG = '''<div class="cfg-item">
<h4>群消息转发显示名 <span class="key">chat-format.qq-forward-display-name</span></h4>
<div class="config">
<pre>chat-format:
  qq-forward-display-name: group_nickname  # group_nickname = 群昵称（默认）/ qq_nickname = QQ 用户名</pre>
</div>
<p>QQ 群消息转发到游戏时，<code>[QQ] 名字: 内容</code> 里的「名字」用哪个：</p>
<p><b>group_nickname（默认）= 群昵称</b>——该成员在群里显示的名字（设置过群名片就是群名片，没设置就是他的 QQ 昵称）；<b>qq_nickname = QQ 用户名</b>——QQ 官方机器人接口在群消息里只返回上面这一个昵称字段，拿不到独立的「QQ 资料昵称」，所以这个选项目前和群昵称显示同一个值（官方将来开放独立接口后生效差异）。只影响全量转发；「/发信息」命令转发的名字固定用群昵称。改完 <code>/hbkrg reload</code> 即时生效。</p>
</div>'''
NEW_CFG = '''<div class="cfg-item">
<h4>群消息转发显示名（固定，无配置项）</h4>
<p>QQ 群消息转发到游戏时，<code>[QQ] 名字: 内容</code> 里的「名字」固定为发送者的<b>群昵称</b>——该成员在群里显示的名字（设置过群名片就是群名片，没设置就是他的 QQ 昵称）。全量转发与「/发信息」命令转发同口径，1.7.5.9 起不再提供配置项（历史版本曾有 chat-format.group-sender-name / chat-format.qq-forward-display-name 两个配置键，均已废弃，升级时自动删除）。</p>
</div>'''
if OLD_CFG in u:
    u = u.replace(OLD_CFG, NEW_CFG)
    assert u.count(NEW_CFG) == 1 and OLD_CFG not in u
    tp.write_text(u, encoding='utf-8')
    print('[ok] tutorial.html：配置条目改固定行为说明')
elif NEW_CFG in u:
    print('[skip] tutorial.html 已是目标态')
else:
    raise SystemExit('tutorial.html 状态非预期')

# ---------- 4. README.md ----------
rm = R.read_text(encoding='utf-8')
SUMMARY = ('> 1.7.5.9（三项 + 覆盖轮）：**转发显示名统一群昵称（覆盖轮去配置化）**——转发到游戏的消息显示名'
           '统一固定为群昵称（消息事件返回的唯一昵称字段：群名片优先、未设置即 QQ 昵称），全量转发与 /发信息 '
           '同口径，不再提供配置项；首发版误加的 chat-format.qq-forward-display-name（官方接口单昵称字段，'
           'group_nickname/qq_nickname 两选项同值）与 1.7.5.8 的 group-sender-name（group|player）两代配置键'
           '入废弃清单，config-version 49 / 50 → 51 升级自动删键；ChatFormat 字段、resolveForwardDisplayName、'
           'GroupSenderName、getBoundPlayerNameByOpenid 全部删除。**菜单模板删除重建**——命中任一历史版本内置'
           '默认（用户没改过）→ 删除磁盘文件重新释放本版默认（先 delete 再 writeBytes，全新文件零追加）；'
           '改过的保留不动；menu.md 历代 4 形态考据收录（1.7.4 及以前不存在菜单模板文件）。**Fabric 绑定限制'
           '策略**——未绑定玩家进服后限制全部互动（锁移动 connection.teleport 锚点 / 禁方块与生物交互三回调 / '
           '禁聊天 / 禁命令 brigadier requirement 包装），绑定码每 5 秒输出（过期自动换新），超时踢出'
           '（qq-bind.restrict-timeout-seconds 默认 120 可配），群里绑定成功 1 秒内自动解限；/hbkrg qbd 删除；'
           '假人无白名单（超时同样踢出）；Spigot/NeoForge 端仍为登录期拦截（1.7.5.8 假人豁免保持），两套策略'
           '差异待用户定夺。覆盖轮并修官网三处首发缺陷（version.json 对象格式+重复条目 / index.html 日志块与'
           '历史表双份 / README 缺摘要段）。门禁 verify_jar_1759o2 196/196 全绿。\n')
ANCHOR_1758 = '> 1.7.5.8（两项）：'
if SUMMARY not in rm:
    assert ANCHOR_1758 in rm, 'README 1.7.5.8 摘要锚点未命中'
    assert '> 1.7.5.9（' not in rm, 'README 已有 1.7.5.9 摘要段？非预期'
    rm = rm.replace(ANCHOR_1758, SUMMARY + '\n' + ANCHOR_1758, 1)

OLD_T1 = ('- **任务1（显示名，覆盖上轮方案）**：新键 `chat-format.qq-forward-display-name`（默认 `group_nickname` 群昵称 / '
          '`qq_nickname` QQ 用户名）。QQ 官方群消息接口只返回一个昵称字段（author.username：群名片优先，未设置即 QQ 昵称'
          '——「card 为空回退 nickname」由该字段天然满足），拿不到独立的 QQ 资料昵称，两个选项当前显示同一个值'
          '（如实写入配置注释，官方开放独立通道后在 `resolveForwardDisplayName` 接入）。仅作用于全量转发；'
          '「/发信息」命令转发的名字保持群昵称（1.7.5.7 行为）。上轮 `group-sender-name`（group|player）整体拆除：'
          '升级自动删旧键，`GroupSenderName` 类与 `getBoundPlayerNameByOpenid` 删除；config-version 49 → 50。')
NEW_T1 = ('- **任务1（显示名，1.7.5.9 覆盖轮去配置化）**：转发到游戏的消息显示名统一固定为群昵称（消息事件返回的唯一昵称字段，'
          '官方语义即该成员在群内显示的名字：群名片优先、未设置即 QQ 昵称——「card 为空回退 nickname」由该字段天然满足），'
          '全量转发与「/发信息」命令转发同口径，不再提供配置项（用户指令「转发不用配置了，直接统一全部默认显示群昵称」）。'
          '首发版误加的 `chat-format.qq-forward-display-name`（group_nickname/qq_nickname 二选一——官方接口单昵称字段'
          '两选项同值，无二选一意义）与 1.7.5.8 的 `chat-sender-name`→`group-sender-name`（group|player）两代配置键入废弃清单；'
          '`ChatFormat.qqForwardDisplayName` 字段、`resolveForwardDisplayName` 解析器、`GroupSenderName` 类与'
          ' `getBoundPlayerNameByOpenid` 全部删除；config-version 49 / 50 → 51，任何历史版本升级自动删键。')
if OLD_T1 in rm:
    rm = rm.replace(OLD_T1, NEW_T1)
OLD_TITLE = '### v1.7.5.9 更新日志（全量转发显示名 qq_nickname 可配 / 菜单模板删除重建 / Fabric 绑定限制策略）'
NEW_TITLE = '### v1.7.5.9 更新日志（转发显示名统一群昵称 / 菜单模板删除重建 / Fabric 绑定限制策略；同号覆盖重发）'
if OLD_TITLE in rm:
    rm = rm.replace(OLD_TITLE, NEW_TITLE)
OLD_VER = ('- 验证：门禁 verify_jar_1759 **184/184** 全绿；冒烟 smoke1759 **20/20** 全绿；'
           'config-version 50（新键升级自动写入，旧键 group-sender-name 自动删除）。')
NEW_VER = ('- **覆盖轮（官网修复）**：首发 1.7.5.9 官网三处缺陷——version.json 更新日志被误改为对象格式且 insert(0) '
           '重复执行产生两条重复对象（UpdateChecker 消费纯字符串数组，对象条目被整段打印）+ 1.7.5.8 旧字符串遗留；'
           'index.html 更新日志块与历史表 1.7.5.9 四行各重复一份；README 摘要段缺失。本轮 web_1759o2 幂等重建全部修复。\n'
           '- 验证：门禁 verify_jar_1759o2 **196/196** 全绿（首发 1759 回归 + 去配置化断言 + 假人豁免 Spigot/NF '
           '三拦截点 + Fabric 限制策略 21 项 + 未映射引用白名单 4 条）；config-version 51（两代显示名键升级自动删除，'
           'restrict-timeout-seconds 保留）。')
if OLD_VER in rm:
    rm = rm.replace(OLD_VER, NEW_VER)
assert NEW_TITLE in rm and NEW_T1 in rm and NEW_VER in rm and SUMMARY in rm
R.write_text(rm, encoding='utf-8')
print('[ok] README.md：摘要段补齐 + 详史节覆盖轮口径')
print('全部完成')
