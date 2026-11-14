#!/usr/bin/env python3
"""1.7.5.9 官网四件 + README 更新（基于线上同底稿 website/，md5 已三方核对）"""
import json
import pathlib
from datetime import datetime

BASE = pathlib.Path('/home/z/my-project/penguin-git')
W = BASE / 'website'

# ---------- 1. version.json ----------
vp = W / 'version.json'
v = json.loads(vp.read_text(encoding='utf-8'))
v['version'] = '1.7.5.9'
v['url'] = v.get('url') or 'https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.5.9.jar'
v['changelog'].insert(0, {
    'version': '1.7.5.9',
    'date': '2026-11-14',
    'items': [
        '新增：QQ 群消息转发到游戏的显示名可选群昵称 / QQ 用户名（chat-format.qq-forward-display-name，默认 group_nickname 群昵称；QQ 官方接口在群消息里只返回一个昵称字段，两个选项当前显示同一个值，官方开放独立接口后生效差异）；「/发信息」命令转发的名字保持群昵称不变',
        '修复：菜单模板替换不完整——命中任一历史版本内置默认（用户没改过）时改为删除文件重新生成本版默认，改过的保留不动',
        '重做：Fabric 版 QQ 绑定策略——未绑定玩家不再自愿取码：进入服务器后限制全部互动（锁定移动 / 禁方块与生物交互 / 禁发消息与命令），聊天栏持续输出绑定码，超时踢出（qq-bind.restrict-timeout-seconds 默认 120 秒可配），群里绑定成功后自动解除全部限制；/hbkrg qbd 取码指令删除',
    ],
})
vp.write_text(json.dumps(v, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('[ok] version.json ->', v['version'], 'changelog', len(v['changelog']), '条')

# ---------- 2. index.html ----------
ip = W / 'index.html'
t = ip.read_text(encoding='utf-8')
# 主下载框
t = t.replace('版本 1.7.5.8</p>', '版本 1.7.5.9</p>')
for old, new in [
    ('KERONGPenguin_Spigot-1.7.5.8.jar', 'KERONGPenguin_Spigot-1.7.5.9.jar'),
    ('KERONGPenguin_NeoForge-1.20.4-1.7.5.8.jar', 'KERONGPenguin_NeoForge-1.20.4-1.7.5.9.jar'),
    ('KERONGPenguin_Fabric-1.20.4-1.7.5.8.jar', 'KERONGPenguin_Fabric-1.20.4-1.7.5.9.jar'),
    ('KERONGPenguin_Fabric-1.20.1-1.7.5.8.jar', 'KERONGPenguin_Fabric-1.20.1-1.7.5.9.jar'),
]:
    # 主框按钮（1.7.5.8→1.7.5.9）：只替换主框四处（下载按钮 href）；历史表通过插入新行保留旧行
    t = t.replace(old, new, 1)  # 每链接首次出现即主框
# 历史表：1.7.5.9 四行置顶（插在 1.7.5.8 首行之前）
anchor = '<tr><td>NeoForge 1.7.5.8</td>'
rows_179 = (
    '<tr><td>NeoForge 1.7.5.9</td><td>2026-11-14</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.9.jar">下载</a></td></tr>\n'
    '<tr><td>Spigot 1.7.5.9</td><td>2026-11-14</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.5.9.jar">下载</a></td></tr>\n'
    '<tr><td>Fabric 1.7.5.9（1.20.4 通用包）</td><td>2026-11-14</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.9.jar">下载</a></td></tr>\n'
    '<tr><td>Fabric 1.7.5.9（1.20.1 独立包）</td><td>2026-11-14</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.5.9.jar">下载</a></td></tr>\n'
    '        ' + anchor
)
assert anchor in t, '历史表锚点未命中'
t = t.replace(anchor, rows_179, 1)
# 更新日志块：v1.7.5.9 置顶
log_anchor = '<div class="version"><h3>v1.7.5.8</h3>'
assert log_anchor in t
log_179 = '''<div class="version"><h3>v1.7.5.9</h3><span class="date">2026-11-14</span><ul>
<li>新增：QQ 群消息转发到游戏的显示名可选群昵称 / QQ 用户名（chat-format.qq-forward-display-name，默认群昵称；QQ 官方接口在群消息里只返回一个昵称字段，两个选项当前显示同一个值）；「/发信息」命令转发的名字保持群昵称不变。</li>
<li>修复：菜单模板替换不完整——命中任一历史版本内置默认（没改过）改为删除文件重新生成本版默认；改过的保留不动。</li>
<li>重做：Fabric 版 QQ 绑定策略——未绑定玩家进入服务器后限制全部互动（锁定移动 / 禁交互 / 禁聊天与命令），聊天栏持续输出绑定码，超时踢出（qq-bind.restrict-timeout-seconds 默认 120 秒可配）；群里绑定成功自动解除全部限制；/hbkrg qbd 取码指令删除。</li>
</ul></div>
''' + log_anchor
t = t.replace(log_anchor, log_179, 1)
ip.write_text(t, encoding='utf-8')
print('[ok] index.html 主框/历史表/日志块')

# ---------- 3. tutorial.html ----------
tp = W / 'tutorial.html'
t = tp.read_text(encoding='utf-8')
# group-sender-name 条目 → qq-forward-display-name
old_cfg = '''<div class="cfg-item">
<h4>群消息发送者显示名 <span class="key">chat-format.group-sender-name</span></h4>
<div class="config">
<pre>chat-format:
  group-sender-name: group  # group = 群昵称（默认）/ player = 绑定玩家名</pre>
</div>
<p>QQ 群消息转发到游戏时，<code>[QQ] 名字: 内容</code> 里的「名字」用哪个：</p>
<p><b>group（默认）= 群昵称</b>——该成员在群里显示的名字（设置过群名片就是群名片，没设置就是他的 QQ 昵称）；<b>player = 绑定玩家名</b>——显示他绑定的游戏玩家名，没绑定的成员仍显示群昵称。改完 <code>/hbkrg reload</code> 即时生效。</p>
</div>'''
new_cfg = '''<div class="cfg-item">
<h4>群消息转发显示名 <span class="key">chat-format.qq-forward-display-name</span></h4>
<div class="config">
<pre>chat-format:
  qq-forward-display-name: group_nickname  # group_nickname = 群昵称（默认）/ qq_nickname = QQ 用户名</pre>
</div>
<p>QQ 群消息转发到游戏时，<code>[QQ] 名字: 内容</code> 里的「名字」用哪个：</p>
<p><b>group_nickname（默认）= 群昵称</b>——该成员在群里显示的名字（设置过群名片就是群名片，没设置就是他的 QQ 昵称）；<b>qq_nickname = QQ 用户名</b>——QQ 官方机器人接口在群消息里只返回上面这一个昵称字段，拿不到独立的「QQ 资料昵称」，所以这个选项目前和群昵称显示同一个值（官方将来开放独立接口后生效差异）。只影响全量转发；「/发信息」命令转发的名字固定用群昵称。改完 <code>/hbkrg reload</code> 即时生效。</p>
</div>'''
assert old_cfg in t, 'tutorial group-sender-name 条目未命中'
t = t.replace(old_cfg, new_cfg, 1)
# Fabric 绑定段（223 行）口径更新 + 新 cfg-item（restrict-timeout-seconds）
old_fa = '<p><b>Fabric 模组版的绑定不拦截进服</b>：未绑定玩家正常游玩，绑定完全自愿——玩家在游戏内发送 <code>/hbkrg qbd</code> 获取验证码，再到 QQ 群发 <code>/绑定 验证码</code>（或直接发纯数字验证码）完成；qq-bind.enabled 在 Fabric 版只控制绑定功能开关，不拦截进服。</p>'
new_fa = '<p><b>Fabric 模组版：未绑定玩家限制互动</b>（1.7.5.9 起的新策略）——进入服务器后锁定移动、禁方块与生物交互、禁发消息与命令，聊天栏持续显示绑定码；到 <code>qq-bind.restrict-timeout-seconds</code>（默认 120 秒）仍未绑定就踢出，再次进入会换新的绑定码；在 QQ 群发 <code>/绑定 验证码</code>（或直接发纯数字验证码）成功后自动解除全部限制。旧的 <code>/hbkrg qbd</code> 取码指令已删除（绑定码自动输出，无需自助查询）；qq-bind.enabled 在 Fabric 版控制整套绑定功能（含限制策略）开关。</p>'
assert old_fa in t, 'tutorial Fabric 绑定段未命中'
t = t.replace(old_fa, new_fa, 1)
# 在 qq-bind 段末尾（kick-message 示例后）插入 restrict-timeout-seconds cfg-item
anchor2 = '<h3>每日签到（qq-bind.checkin 段）</h3>'
assert anchor2 in t
cfg_restrict = '''<div class="cfg-item">
<h4>Fabric 未绑定限制超时 <span class="key">qq-bind.restrict-timeout-seconds</span></h4>
<div class="config">
<pre>qq-bind:
  restrict-timeout-seconds: 120  # 未绑定玩家限制 N 秒后踢出（Fabric 版）</pre>
</div>
<p>Fabric 模组版专属：未绑定玩家进入服务器后被限制全部互动，聊天栏持续输出绑定码；超过这里的秒数仍未绑定就踢出服务器（再次进入会换新绑定码）。改完 <code>/hbkrg reload</code> 即时生效；Spigot / NeoForge 版为登录期拦截，无此限制阶段。</p>
</div>

'''
t = t.replace(anchor2, cfg_restrict + anchor2, 1)
tp.write_text(t, encoding='utf-8')
print('[ok] tutorial.html 两条目改 + 一条目新增')

# ---------- 4. README ----------
rp = BASE / 'README.md'
t = rp.read_text(encoding='utf-8')
t = t.replace('当前版本：**1.7.5.8**', '当前版本：**1.7.5.9**')
anchor3 = '### v1.7.5.8 更新日志（群消息转发显示名可配置 / QQ 绑定假人豁免）'
assert anchor3 in t
readme_179 = '''### v1.7.5.9 更新日志（全量转发显示名 qq_nickname 可配 / 菜单模板删除重建 / Fabric 绑定限制策略）

- **任务1（显示名，覆盖上轮方案）**：新键 `chat-format.qq-forward-display-name`（默认 `group_nickname` 群昵称 / `qq_nickname` QQ 用户名）。QQ 官方群消息接口只返回一个昵称字段（author.username：群名片优先，未设置即 QQ 昵称——「card 为空回退 nickname」由该字段天然满足），拿不到独立的 QQ 资料昵称，两个选项当前显示同一个值（如实写入配置注释，官方开放独立通道后在 `resolveForwardDisplayName` 接入）。仅作用于全量转发；「/发信息」命令转发的名字保持群昵称（1.7.5.7 行为）。上轮 `group-sender-name`（group|player）整体拆除：升级自动删旧键，`GroupSenderName` 类与 `getBoundPlayerNameByOpenid` 删除；config-version 49 → 50。
- **任务2（菜单模板，用户指定方案）**：命中任一历史版本内置默认（= 用户没改过）→ **删除磁盘文件重新释放本版默认**（先 delete 再 writeBytes，全新文件零追加）；与所有历史默认都不一致（= 用户改过）→ 保留不动，不得误删。menu.md 历代 4 形态 git 考古全量收录（1.7.4 及以前不存在菜单模板文件——menu.md 于 1.7.5 引入）。
- **任务3（Fabric 绑定策略覆盖）**：未绑定玩家不再自愿取码——进入服务器后限制全部互动：锁定移动（每 tick 锚点锁定 `connection.teleport`）、禁方块交互（UseBlock/AttackBlock FAIL）、禁生物交互（UseEntity FAIL）、禁发消息（ALLOW_CHAT 拦截，不广播不转发群）、禁命令（brigadier 命令树 requirement 包装 `BindRestrictCommandGate`——Unsafe 替换 requirement，受限玩家 parse 任何命令按未知命令拒绝；/me /say 另由 ALLOW_COMMAND_MESSAGE 拦截）；聊天栏每 5 秒输出绑定码（过期自动换新）；超时踢出（`qq-bind.restrict-timeout-seconds` 默认 120 秒）；再次进入刷新绑定码；群里绑定成功 tick 轮询 1 秒内自动解限；`/hbkrg qbd` 删除。假人无白名单（可加入但超时同样被踢）。Spigot / NeoForge 端仍为登录期拦截（1.7.5.8 假人豁免保持），两套策略差异待用户定夺。
- 验证：门禁 verify_jar_1759 **184/184** 全绿；冒烟 smoke1759 **20/20** 全绿；config-version 50（新键升级自动写入，旧键 group-sender-name 自动删除）。

'''
t = t.replace(anchor3, readme_179 + anchor3, 1)
rp.write_text(t, encoding='utf-8')
print('[ok] README 当前版本+详史节')

print('web_1759 全部完成')
