#!/usr/bin/env python3
"""1.7.6.3 README 更新：功能列表 AI 条目 + 当前版本 + 摘要置顶 + v1.7.6.3 详史节。"""
import pathlib
import sys

README = pathlib.Path("/home/z/my-project/repo/README.md")

FEATURES_OLD = "带**表情包**（guaipng 文件夹，文件名=含义，动态 GIF 正常发送）、**提示词文件**（单文件 提示词.md，角色设定+专属知识都写在这）与**网页爬取**（默认关，测试功能）三大能力"
FEATURES_NEW = "带**表情包**（guaipng 文件夹，文件名=含义，动态 GIF 正常发送）、**提示词文件**（单文件 提示词.md，角色设定+专属知识都写在这）与**网页读取**（正式功能默认开，多次多层深入，搜索走必应）三大能力"

VER_OLD = "当前版本：**1.7.6.2**"
VER_NEW = "当前版本：**1.7.6.3**"

SUMMARY = "> 1.7.6.3：**AI 网页读取转正式功能**——`ai.crawler.enabled` 默认开（测试期默认关升级自动收敛，可改回）；**多次多层读取**——回传正文以「文字（网址）」保留链接，模型可继续 [GET] 逐层深入（搜索页 → 具体页面），一次回答上限 `ai.crawler.max-pages`（默认 2 → 3）；**必应搜索指引**——能力段写明需要搜索时用 bing.com（`WebCrawler.normalizeUrl` 对非 ASCII 关键词百分号编码，中文搜索可直接用）；**token 消耗提示**——config 注释与教程标明每次读取按页计消耗额外 token。**修复旧知识库文件夹残留**——旧版生成的 knowledge 文件夹不再创建不再读取，启动自动清理（`AiPromptFile.cleanupLegacyKnowledgeFolder`：空/只剩旧说明文件自动删，有用户文件保留+提示）。**配置简化**——删四个调优键（`ai.memes.folder` 固定 guaipng / `ai.memes.max-per-reply` 固定 1 / `ai.crawler.timeout-seconds` 固定 10s / `ai.crawler.max-chars` 固定 6000，升级自动删键，自定义过表情包文件夹名的启动提示移入 guaipng）；AI 段保留 memes.enabled / prompt-file / crawler.enabled / crawler.max-pages 四键；config-version 57 → 58。\n"

DETAIL_HEAD = "### v1.7.6.3 更新日志（AI 网页读取转正式功能+多次多层+必应搜索+token 提示+旧知识库文件夹清理+配置简化；1.7.6.2 见下方）\n\n"

DETAIL_BODY = """- **任务1（爬取转正式版，用户口径「把AI网页抓取功能变为正式版」）**：`ai.crawler.enabled` 默认关 → 开——QqBindManager 读取默认值与 catch 兜底改 true；旧测试期的 false 是插件默认写入而非用户意图，`ConfigFileRebuilder` 升 58 收敛块自动改写为 true（自行开启过的本就是 true 不动；不想要的服主可改回 false）；三端 config.yml 注释整段重写为正式功能口径（去测试警告、加 token 消耗提醒与必应说明）；能力段（AiChat.capabilitySection）去「测试功能，结果可能不准」字样，补「多次多层读取 + 必应搜索 + 链接逐层深入」指引。
- **任务2（多次多层爬取）**：`WebCrawler.extractText` 增加 baseUrl 参数——以最终落点 URL 为基准 `setBaseUri`，`<a href>` 经 `abs:href` 解析为绝对地址后以「文字（网址）」形式保留在正文文本里（仅 http/https 链接，相对链接自动补全），模型看得到下一层链接即可继续 [GET] 逐层深入；对话循环机制本就支持多轮多 URL（每轮 take 剩余额度、轮次护栏 maxFetch+3），`ai.crawler.max-pages` 默认 2 → 3（搜索页 + 两层深入）；能力段与 config 注释同步多层口径。
- **任务3（必应搜索指引）**：能力段写明「需要搜索时用必应搜索引擎，如 [GET] https://www.bing.com/search?q=关键词」；`WebCrawler.normalizeUrl`（纯函数）把网址里的非 ASCII 字符与空格百分号编码（ASCII 可打印字符与已编码 %XX 原样保留）——Java HttpURLConnection 不做自动编码，模型输出的中文关键词搜索网址不加编码会被服务器拒绝；`parseAndValidate` 入口统一走归一。实测 bing.com 搜索页 200 + 搜索结果可提取（www → cn 重定向自动跟随）。
- **任务4（token 消耗提示，用户口径「AI网页爬取写明可能会消耗更多token」）**：三端 config.yml crawler 段注释 + tutorial.html 爬取条目 + 官网更新日志均标明「每次读取的网页内容都会消耗额外 token（按 max-pages 页计），接口按 token 计费时请留意用量」。
- **任务5（旧知识库文件夹清理，用户口径「修复为什么还会自动生成知识库的文件夹？现在不是单个文件吗？」）**：根因——1.7.6.2 首发轮及更早版本会自动创建 knowledge 文件夹，覆盖轮虽删了创建代码，但磁盘上已生成的文件夹不会被清掉（用户从旧版升级后仍会看到，误以为还在生成）。修复——`AiPromptFile.cleanupLegacyKnowledgeFolder`：空文件夹 / 只剩旧版自动生成的说明文件（说明-把知识文件放进这里.txt）→ 自动删除整个文件夹；有用户自己放的文件 → 保留不动 + 日志提示整理进提示词文件后手动删除；`ConfigManager.cleanupLegacyFolders`（配置重建之前执行）读磁盘旧配置的 ai.knowledge.folder 自定义名与默认 knowledge 都查。
- **任务6（配置简化，用户口径「简化最近新增的配置文件」）**：删四个调优键（REMOVED_CONFIG_KEYS 升 58 自动删，行为固定为原默认值）：`ai.memes.folder`（表情包文件夹固定 guaipng——`AiMemeLibrary.FOLDER_NAME` 常量，自定义过文件夹名且旧文件夹有图的启动时 warning 提示移入）、`ai.memes.max-per-reply`（固定 1——`AiChat.MEME_MAX_PER_REPLY` 常量）、`ai.crawler.timeout-seconds`（固定 10s——`WebCrawler.DEFAULT_TIMEOUT_SECONDS`）、`ai.crawler.max-chars`（固定 6000——`WebCrawler.DEFAULT_MAX_CHARS`）；QqBindManager 对应读取函数删除；三端 config.yml AI 段缩为四键（memes.enabled / prompt-file / crawler.enabled / crawler.max-pages）。简化与删除字段清单已写入 AI 交接文档。
- **基建**：config-version 57 → 58（删键 4 + crawler.enabled 收敛 + knowledge 文件夹清理）；版本号六处 1.7.6.2 → 1.7.6.3（build.gradle / plugin.yml / mods.toml 双文件 / fabric.mod.json / KERONGNeoForge.kt MOD_VERSION【补上轮漏更的 1.7.6.1】）；官网三件与教程同步；门禁 verify_jar_1763 + 冒烟 smoke1763（normalizeUrl 编码/extractText 链接保留/清理三分支/能力段正式版文案/回归）。构建坑合规：rm -rf 四模块 build 全量重编 + Fabric remap 缓存删除重打两版 + base=1.7.6.2。

"""


def main():
    t = README.read_text(encoding="utf-8")

    if "1.7.6.3" in t:
        print("[SKIP] README 已含 1.7.6.3（幂等）")
        return 0

    # ① 功能列表
    assert FEATURES_OLD in t, "功能列表 AI 条目未命中"
    t = t.replace(FEATURES_OLD, FEATURES_NEW, 1)
    # ② 当前版本
    assert VER_OLD in t
    t = t.replace(VER_OLD, VER_NEW, 1)
    # ③ 摘要置顶（插在 1.7.6.2 摘要行之前）
    anchor = "> 1.7.6.2（首发+覆盖轮）："
    assert anchor in t
    t = t.replace(anchor, SUMMARY + anchor, 1)
    # ④ 详史节（插在 v1.7.6.2 标题之前）
    anchor2 = "### v1.7.6.2 更新日志"
    assert anchor2 in t
    t = t.replace(anchor2, DETAIL_HEAD + DETAIL_BODY + anchor2, 1)

    assert t.count("1.7.6.3") >= 3
    README.write_text(t, encoding="utf-8")
    print("[OK] README 更新完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
