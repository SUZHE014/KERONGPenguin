#!/usr/bin/env python3
"""1.7.6.3 四次覆盖轮官网三件 + README 更新（幂等：目标态已达成则跳过）。

改动口径（用户指令「AI搜索默认配置改为关 / AI 提示词技能引导生成改为仅在
AI搜索配置为开的情况下才生成（不用兼容旧版自动清理旧有的提示词）」）：
- version.json：1.7.6.3 changelog 追加 ⑧⑨ 两条（默认改关 + 技能引导条件生成）；
- index.html：v1.7.6.3 日志块追加两条 li；
- tutorial.html：AI 网页读取条目改默认关口径 + 提示词条目补「搜索引导仅在
  网页读取开启时写入默认模板」；
- README.md：功能概览网页读取改默认关 + 最近版本清单置顶四次覆盖轮一条 +
  详细段落新增四次覆盖轮一节。
"""
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CHANGED = []


def patch(path, old, new, mark, idem_mark=None):
    """idem_mark：目标态探测片段（new 的标志性内容）；不提供时用 new 全文
    （后续追加型补丁会改变前序补丁的尾部，此时必须提供）。"""
    full = os.path.join(REPO, path)
    with open(full, encoding="utf-8") as f:
        text = f.read()
    probe = idem_mark if idem_mark else new
    if probe in text:
        print(f"[SKIP] {path} 已是目标态（{mark}）")
        return
    if old not in text:
        print(f"[MISS] {path} 锚点未匹配（{mark}），请人工检查")
        sys.exit(1)
    text = text.replace(old, new, 1)
    with open(full, "w", encoding="utf-8") as f:
        f.write(text)
    CHANGED.append(path)
    print(f"[OK] {path}（{mark}）")


# ---------- version.json：追加 ⑧⑨ ----------
VJ_OLD = '''    "⑦ 修复：AI 只发一个表情包时不再先发一条空白的「[AI]」消息——直接发表情包图片；表情没匹配到图片或图片发送失败时发一句文字说明兜底；游戏内对话同步修复"
  ]'''
VJ_NEW = '''    "⑦ 修复：AI 只发一个表情包时不再先发一条空白的「[AI]」消息——直接发表情包图片；表情没匹配到图片或图片发送失败时发一句文字说明兜底；游戏内对话同步修复",
    "⑧ 调整：AI 网页读取（搜索）默认改为关——已开启的服务器保持开启不受影响，想关在配置里改 ai.crawler.enabled: false 即可；老版本升级不再被强制打开",
    "⑨ 调整：提示词里的「遇到不会的可以搜索」技能引导改为仅在 AI 网页读取开启时才生成（默认模板按当前开关写入；关闭时对话内系统技能段也不注入搜索条目，不引导 AI 去用没有的功能）；已生成的提示词文件不自动改动"
  ]'''
patch("website/version.json", VJ_OLD, VJ_NEW, "version.json ⑧⑨", idem_mark='"⑧ 调整：AI 网页读取（搜索）默认改为关')

# ---------- index.html：v1.7.6.3 块追加两条 li ----------
IX_OLD = '''<li>修复：AI 只发一个表情包时不再先发一条只有「[AI]」前缀的空白消息——直接发表情包图片；表情没匹配到图片（可检查表情包文件夹）或图片发送失败时发一句文字说明兜底；游戏内对话同步修复（不再广播空白）</li>
</ul></div>'''
IX_NEW = '''<li>修复：AI 只发一个表情包时不再先发一条只有「[AI]」前缀的空白消息——直接发表情包图片；表情没匹配到图片（可检查表情包文件夹）或图片发送失败时发一句文字说明兜底；游戏内对话同步修复（不再广播空白）</li>
<li>调整：<b>AI 网页读取（搜索）默认改为关</b>——新装默认不开启；已开启的服务器保持开启不受影响，想关在配置里改 ai.crawler.enabled: false 即可；老版本升级也不再被强制打开</li>
<li>调整：提示词里的「遇到不会的可以搜索」<b>技能引导仅在 AI 网页读取开启时才生成</b>（默认模板按当前开关写入；关闭时对话内系统技能段也不注入搜索条目，不引导 AI 去用没有的功能）；已生成的提示词文件不自动改动</li>
</ul></div>'''
patch("website/index.html", IX_OLD, IX_NEW, "index.html 两条 li", idem_mark="<li>调整：<b>AI 网页读取（搜索）默认改为关</b>")

# ---------- tutorial.html：网页读取条目默认关 ----------
TT_OLD = '''<h4>AI 网页读取（正式功能） <span class="key">ai.crawler.*</span></h4>
<div class="config">
<pre>ai:
  crawler:
    enabled: true           # 正式功能，默认开；改 false 关闭
    max-pages: 8            # 单次回答最多读取的网页总数（各层累计）
    max-layers: 4           # 多层深入的最大层数（第 1 层 = 入口/搜索页）
    max-chars: 6000         # 单页正文提取上限（字符）
    timeout-seconds: 10     # 读取超时秒数</pre>
</div>
<p><b>默认开启</b>。AI 需要看网页或搜索资料时自动读网页'''
TT_NEW = '''<h4>AI 网页读取 <span class="key">ai.crawler.*</span></h4>
<div class="config">
<pre>ai:
  crawler:
    enabled: false          # 默认关；改 true 开启
    max-pages: 8            # 单次回答最多读取的网页总数（各层累计）
    max-layers: 4           # 多层深入的最大层数（第 1 层 = 入口/搜索页）
    max-chars: 6000         # 单页正文提取上限（字符）
    timeout-seconds: 10     # 读取超时秒数</pre>
</div>
<p><b>默认关闭</b>（此前版本默认开启，已开启的服务器保持开启，想关改 enabled: false 即可）。开启后 AI 需要看网页或搜索资料时自动读网页'''
patch("website/tutorial.html", TT_OLD, TT_NEW, "tutorial 爬取默认关")

# 爬取条目末尾补提示词联动说明
TT2_OLD = '''AI 对话与网页读取全程在<b>异步线程</b>执行（不占游戏主线程，不影响 TPS）；爬取与 AI 生成同线程串行，只影响该条回复自身的耗时。</p>'''
TT2_NEW = '''AI 对话与网页读取全程在<b>异步线程</b>执行（不占游戏主线程，不影响 TPS）；爬取与 AI 生成同线程串行，只影响该条回复自身的耗时。关闭时提示词默认模板不带「遇到不会的可以搜索」引导、对话内系统技能段也不注入搜索条目（不会引导 AI 去用没有的功能）。</p>'''
patch("website/tutorial.html", TT2_OLD, TT2_NEW, "tutorial 爬取-提示词联动")

# 提示词条目补条件生成口径
TT3_OLD = '''（新装默认模板自带，对话内系统还会按配置自动注入「系统技能」段，以系统注入为准）'''
TT3_NEW = '''（新装默认模板自带——其中搜索引导仅在 AI 网页读取开启时才写入；对话内系统还会按配置自动注入「系统技能」段，以系统注入为准；已生成的提示词文件不随开关自动改动）'''
patch("website/tutorial.html", TT3_OLD, TT3_NEW, "tutorial 提示词条件生成")

# ---------- README.md ----------
# 1. 功能概览
RD_OLD = '''**网页读取**（正式功能默认开，多次多层深入，搜索走必应）三大能力'''
RD_NEW = '''**网页读取**（默认关，开启后可多次多层深入，搜索走必应；提示词技能引导随开关生成）三大能力'''
patch("README.md", RD_OLD, RD_NEW, "README 功能概览")

# 2. 最近版本清单置顶一条（三次覆盖轮行前插）
RD2_OLD = '''> 1.7.6.3 三次覆盖轮：**AI 提示词默认带技能引导**'''
RD2_NEW = '''> 1.7.6.3 四次覆盖轮：**AI 搜索默认改关 + 提示词技能引导条件生成**（用户口径「AI搜索默认配置改为关；AI 提示词技能引导生成改为仅在AI搜索配置为开的情况下才生成（不用兼容旧版自动清理旧有的提示词）」——ai.crawler.enabled 默认 true→false（DEFAULT_VALUES + 三端 config.yml + 运行时读取兜底同步；不升 config-version、不做旧值收敛——已开启的服务器保持开启，自行关闭即可；升 58 的 false→true 收敛块同步移除，老版本升级不再被强制打开）；AiPromptFile.defaultContent/ensureFile/migrateFromSystemPrompt 增加 crawlerEnabled 参数——「遇到不会的可以搜索」等搜索引导仅在 ai.crawler.enabled 开启时写入默认模板（关=只写发表情包技能；已生成的提示词文件一律不动，不做自动清理）；AiChat.capabilitySection 动态系统技能段本就仅在开启时注入搜索条目（口径注释同步）。门禁 verify_jar_1763r 全绿；冒烟 smoke1763r 全绿）。
> 1.7.6.3 三次覆盖轮：**AI 提示词默认带技能引导**'''
patch("README.md", RD2_OLD, RD2_NEW, "README 置顶四次覆盖轮", idem_mark="> 1.7.6.3 四次覆盖轮：**AI 搜索默认改关 + 提示词技能引导条件生成**")

# 3. 详细段落新增一节（三次覆盖轮节前插）
RD3_OLD = '''### 1.7.6.3 三次覆盖轮（提示词技能引导 + 纯表情包空白消息修复；二次覆盖轮见下方）'''
RD3_NEW = '''### 1.7.6.3 四次覆盖轮（AI 搜索默认改关 + 提示词技能引导条件生成；三次覆盖轮见下方）

- **任务1（AI 搜索默认配置改为关）**：`ai.crawler.enabled` 默认 true → false——`ConfigManager.DEFAULT_VALUES` + 三端 config.yml 模板（注释同步：段标题「正式功能，默认开」→「默认关」，补「关闭时提示词不带搜索引导，AI 不读网页不搜索」）+ `QqBindManager.isAiCrawlerEnabled` 运行时兜底默认同步改 false（配置缺键/读取异常时不再当开）。**不升 config-version（保持 60）、不做旧值收敛**（用户口径「不用兼容旧版」）：已开启的 1.7.6.3 服务器保持开启，想关自行改配置即可；**移除 ConfigFileRebuilder 升 58 的 false→true 收敛块**——该块若保留会把 1.7.6.2 及更早升级用户的关闭状态强制改开，与「默认改关」相反；移除后老版本升级按各自磁盘值/新默认（关）走。
- **任务2（提示词技能引导仅在 AI 搜索开启时生成）**：`AiPromptFile.defaultContent(role, crawlerEnabled)` 增参——「- 读网页与搜索：遇到不会、没把握或时效性的问题……先搜索再回答，不要凭记忆猜测，也不要直接说不知道」整行仅在 crawlerEnabled=true 时写入默认模板（关 = 技能段只保留发表情包 + 「以系统技能段为准」两行）；`ensureFile(file, crawlerEnabled)`（QqBindManager 启动生成时传 isAiCrawlerEnabled）与 `migrateFromSystemPrompt(file, oldRole, crawlerEnabled)`（ConfigManager 迁移时读磁盘旧配置，旧版本无键按关）同步传参。**已生成的提示词文件一律不动、不做自动清理**（既定口径；动态系统技能段每次对话按当前配置注入，旧文件里的搜索引导在关闭时也会被「技能是否可用以系统技能段为准」对冲）。`AiChat.capabilitySection` 动态段本就仅在开启时注入「读网页与搜索」条目（1.7.6.3 首发轮实现），本轮仅同步注释口径。
- **官网三件**：version.json 1.7.6.3 changelog 追加 ⑧⑨ 两条；index.html v1.7.6.3 块追加两条 li；tutorial.html 网页读取条目改默认关口径 + 补「关闭时提示词不带搜索引导」+ 提示词条目补条件生成说明。

### 1.7.6.3 三次覆盖轮（提示词技能引导 + 纯表情包空白消息修复；二次覆盖轮见下方）'''
patch("README.md", RD3_OLD, RD3_NEW, "README 详细节", idem_mark="### 1.7.6.3 四次覆盖轮（AI 搜索默认改关 + 提示词技能引导条件生成；三次覆盖轮见下方）")

# ---------- version.json：追加 ⑩ 迁移残留修复 ----------
VJ2_OLD = '''    "⑨ 调整：提示词里的「遇到不会的可以搜索」技能引导改为仅在 AI 网页读取开启时才生成（默认模板按当前开关写入；关闭时对话内系统技能段也不注入搜索条目，不引导 AI 去用没有的功能）；已生成的提示词文件不自动改动"
  ]'''
VJ2_NEW = '''    "⑨ 调整：提示词里的「遇到不会的可以搜索」技能引导改为仅在 AI 网页读取开启时才生成（默认模板按当前开关写入；关闭时对话内系统技能段也不注入搜索条目，不引导 AI 去用没有的功能）；已生成的提示词文件不自动改动",
    "⑩ 修复：插件数据迁入 globalconfig/ 后旧文件残留——同名冲突时旧版直接跳过（旧文件与旧目录永久留在插件根目录），现在按修改时间取较新内容、另一份自动删除；文件被占用导致移动失败时自动改为复制后删除，仍有残留会在控制台提示并下次启动重试"
  ]'''
patch("website/version.json", VJ2_OLD, VJ2_NEW, "version.json ⑩")

# ---------- index.html：追加迁移修复 li ----------
IX2_OLD = '''<li>调整：提示词里的「遇到不会的可以搜索」<b>技能引导仅在 AI 网页读取开启时才生成</b>（默认模板按当前开关写入；关闭时对话内系统技能段也不注入搜索条目，不引导 AI 去用没有的功能）；已生成的提示词文件不自动改动</li>
</ul></div>'''
IX2_NEW = '''<li>调整：提示词里的「遇到不会的可以搜索」<b>技能引导仅在 AI 网页读取开启时才生成</b>（默认模板按当前开关写入；关闭时对话内系统技能段也不注入搜索条目，不引导 AI 去用没有的功能）；已生成的提示词文件不自动改动</li>
<li>修复：<b>插件数据迁入 globalconfig/ 后旧文件残留</b>——同名冲突时旧版直接跳过（旧文件与旧目录永久留在插件根目录），现在按修改时间取较新内容、另一份自动删除；文件被占用导致移动失败时自动改为复制后删除，仍有残留会在控制台提示并下次启动重试</li>
</ul></div>'''
patch("website/index.html", IX2_OLD, IX2_NEW, "index.html 迁移修复 li")

# ---------- README：置顶行补迁移修复 ----------
RD4_OLD = '''门禁 verify_jar_1763r 全绿；冒烟 smoke1763r 全绿）。'''
RD4_NEW = '''门禁 verify_jar_1763r 全绿；冒烟 smoke1763r 全绿）+**修复数据迁移后旧文件残留**（用户口径「修复插件文件迁移后没有删除旧的的问题」——`DataLayout` 迁入 globalconfig 时同名冲突旧版直接跳过（旧文件与旧目录永久残留根目录），现在按修改时间取较新内容另一份删除（降级期间写入的较新数据也不丢）；renameTo 失败（Windows 文件占用等）退化为复制后删除，仍失败保留原位下次启动重试（幂等自愈），有残留时控制台提示）。'''
patch("README.md", RD4_OLD, RD4_NEW, "README 置顶行补迁移修复")

# ---------- README：详细节补任务 3 ----------
RD5_OLD = '''- **官网三件**：version.json 1.7.6.3 changelog 追加 ⑧⑨ 两条；index.html v1.7.6.3 块追加两条 li；tutorial.html 网页读取条目改默认关口径 + 补「关闭时提示词不带搜索引导」+ 提示词条目补条件生成说明。'''
RD5_NEW = '''- **任务3（修复插件文件迁移后旧文件残留，用户口径「修复插件文件迁移后没有删除旧的的问题」）**：`DataLayout.migrate` 三处修复——① 同名冲突（旧位置与 globalconfig 都有同名文件/目录）旧版 `dest.exists() → continue` 直接跳过，旧文件留在源目录、`source.delete()` 只删空目录导致目录也删不掉，永久残留根目录；现改为 `resolveSameName`：按修改时间取较新内容（旧位置较新 = 降级期间写入的数据，先 `copyTo(overwrite=true)` 覆盖到新位置再删旧位置；新位置较新或相等直接删旧位置），删除失败保留原位下次启动重试（覆盖后的新文件 mtime 为当下，重试自然走删除分支，幂等自愈）② `renameTo` 失败（Windows 文件占用/杀毒扫描等）旧版静默放弃旧文件永久残留；现 `moveWithFallback` 退化为复制后删除（目录 `copyRecursively` + `deleteRecursively`），复制失败才保留（数据安全优先）③ `migrateToGlobalLayout` 迁移后扫描根目录残留项（MOVE_DIRS/MOVE_FILES 里仍存在的），有残留时控制台 warning 提示（文件被占用可能、下次启动自动重试、确认 globalconfig 完整后可手动删）。散文件同名分支同步从无条件 `old.delete()` 改为 `resolveSameName`（降级数据不丢）。
- **官网三件**：version.json 1.7.6.3 changelog 追加 ⑧⑨⑩ 三条；index.html v1.7.6.3 块追加三条 li；tutorial.html 网页读取条目改默认关口径 + 补「关闭时提示词不带搜索引导」+ 提示词条目补条件生成说明。'''
patch("README.md", RD5_OLD, RD5_NEW, "README 详细节补任务3")

print("---")
print("changed:", CHANGED if CHANGED else "无（全部已为目标态）")
