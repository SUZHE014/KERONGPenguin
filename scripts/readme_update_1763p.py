#!/usr/bin/env python3
"""README 1.7.6.3 二次覆盖轮插入（行前缀锚定，不依赖整行匹配）。"""
import sys

PATH = "/home/z/my-project/repo/README.md"

QUOTE = "> 1.7.6.3 二次覆盖轮：**单次爬取上限 8 + 多层层数上限 4**（用户口径「把单次网页爬取上限改为8次，多层爬取上限默认改为4次（或者有更好的方法）」——原一行注释把两个概念挤在一起，拆成两个独立键：`ai.crawler.max-pages` 默认 3→8（单次回答读取的网页总数，各层累计），新增 `ai.crawler.max-layers` 默认 4（多层深入的最大层数，第 1 层 = 入口/搜索页）；对话循环达上限时不再直接断路——追加一轮「预算用尽」反馈让模型基于已读内容把回答说完（`AiChat.exhaustedFeedback`，旧版多层深入中途达上限用户只能拿到一句死路提示）；升级自动收敛旧默认 2/3 → 8，自定义值不动）+**全键注释覆盖**（用户口径「确保每个配置都有注释，仔细检查」——三端 config.yml 213 键审计，28 键/端补注释：update-check.enabled、bot.secret、cluster.name、进出服播报 enabled/format、server-notify 四段与 enabled、motd.server-port、online-list-builtin-background、image-download.enabled、code-length、checkin enabled/prefix、admin.mode、qq-output-prefix、memes.enabled、crawler.enabled 等）+**Git 仓库只保留最新两个版本**（用户口径「Git只保留最新的前两个版本」——build/dist 里 1.7.5.2–1.7.6.1 的 35 个 JAR（四平台全量）+ 5 个旧源码包 + build/base 旧底包共 41 件 git rm，仅留 1.7.6.2/1.7.6.3 两版；此后每轮发版同步裁剪）。\n"

SECTION = """### 1.7.6.3 二次覆盖轮（单次上限 8 + 多层 4 层 + 全键注释 + Git 版本裁剪；覆盖轮见下方）

- **任务1（爬取上限拆分，用户口径「多层网页爬去会不会因为单次网页爬取上限只有3而失败，把单次网页爬取上限改为8次，多层爬取上限默认改为4次（或者有更好的方法）」）**：确认旧风险属实——原实现只有 `ai.crawler.max-pages` 一个键（默认 3）同时充当「单次回答读取页数」与「多层爬取上限」，多层深入时 3 页总预算很容易中途达上限断路，且旧版达上限直接把读取请求当最终回复返回（用户只能拿到一句死路提示）。拆分为两个独立键：`ai.crawler.max-pages` 默认 3→8（单次回答读取的网页总数，各层累计含搜索页）+ 新增 `ai.crawler.max-layers` 默认 4（多层深入的最大层数，第 1 层 = 入口/搜索页，逐层 +1）。`runConversation` 重构：层数计数 `layers`（每抓取一轮 +1）与页数计数 `fetched` 双轨；达到任一上限时先追加一轮「预算用尽」反馈（`exhaustedFeedback(maxPages, maxLayers)`：不再输出 [GET]，基于已读内容直接作答，信息不足时说明可能不够准）——只追加一次，防模型反复要读无限拉长对话；反馈后仍要读才按兜底文案返回。轮次护栏 maxFetch+3 → maxPages+maxLayers+4。能力段同步：每次回答最多读 8 页、最多深入 4 层，达到上限后直接基于已读内容回答。日志带层号（第 N 层，X/8 页）。
- **任务2（升级收敛）**：旧默认 2（1.7.6.2 首发 fillMissing）/ 3（1.7.6.3 首发轮）是默认写入而非用户意图——`ConfigFileRebuilder` 升级块与 reload 迁移双路径都把 2/3 收敛为 8（自行改过如 5 的不动）；新键 max-layers 由 fillMissing 补入（默认 4）。三端 config.yml crawler 段五键全注释。
- **任务3（全键注释，用户口径「确保每个配置都有注释，仔细检查」）**：新增 `scripts/audit_config_comments.py` 审计器（每个键：同行 # 或紧邻上方非空行是注释）——初审 28 键/端无注释，`fix_config_comments_1763p.py` 锚定补齐（update-check.enabled / bot.secret / cluster.name / join·quit·death 的 enabled·format / server-notify 四段与 enabled / motd.server-port / online-list-builtin-background / image-download.enabled / code-length / checkin enabled·prefix / admin.mode / qq-output-prefix / memes.enabled / crawler.enabled），复审三端 213 键全部有注释 PASS；门禁与冒烟各带一份同逻辑断言。
- **任务4（Git 只保留最新两个版本，用户口径「Git只保留最新的前两个版本」）**：`git rm` build/dist 里 1.7.5.2–1.7.6.1 的 22 个 JAR（四平台 × 五个旧版本号）+ 5 个旧源码包 + build/base 1.7.5.2 旧底包，仅保留 1.7.6.2 / 1.7.6.3 两版全部产物（含本轮重打的 1.7.6.3 同名覆盖）。
- **基建**：config-version 59 → 60；版本号保持 1.7.6.3 同号三连覆盖；官网三件重写 v1.7.6.3 口径（两个上限分开配置 + 达上限不再中断 + 全键注释 + cv60 收敛说明），教程爬取条目补异步/性能说明（AI 与爬取全程异步线程，不占主线程）。

"""


def insert_before(lines, prefix, block):
    for i, ln in enumerate(lines):
        if ln.startswith(prefix):
            out = lines[:i] + block.splitlines(keepends=True) + lines[i:]
            return out, True
    return lines, False


def main():
    with open(PATH, encoding="utf-8") as fh:
        lines = fh.readlines()
    ok = True
    lines, hit = insert_before(lines, "> 1.7.6.3 覆盖轮：", QUOTE)
    if not hit:
        print("[FAIL] 未找到引言行锚点 > 1.7.6.3 覆盖轮：")
        ok = False
    lines, hit = insert_before(lines, "### 1.7.6.3 覆盖轮（", SECTION)
    if not hit:
        print("[FAIL] 未找到章节锚点 ### 1.7.6.3 覆盖轮（")
        ok = False
    if not ok:
        return 1
    with open(PATH, "w", encoding="utf-8") as fh:
        fh.writelines(lines)
    print("[OK] README 插入完成（引言行 + 章节块）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
