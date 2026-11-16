#!/usr/bin/env python3
"""1.7.6.3 覆盖轮 README 更新：摘要行前插覆盖轮段 + 详史前插覆盖轮节。"""
import pathlib
import sys

README = pathlib.Path("/home/z/my-project/repo/README.md")

SUMMARY = ("> 1.7.6.3 覆盖轮：**注释简化**（用户口径「我说的简化是注释简化」——最近新增配置段注释精简为每段一两行、每键一行，键与行为不变）+"
           "**恢复三键**（ai.memes.max-per-reply / ai.crawler.timeout-seconds / ai.crawler.max-chars，升 58 误删，默认值与历史一致；ai.memes.folder 不恢复，"
           "表情包文件夹固定 guaipng）+**多层爬取上限可配置**（ai.crawler.max-pages 默认 3）+**表情包缩小**（静态图发送前缩到最长边 240px 转单帧 GIF——"
           "QQ 对 GIF 按表情尺寸渲染（动图实测是表情包大小），`AiMemeLibrary.shrinkForSend` 纯函数：GIF 原样直传 / 静态图 ImageIO 缩放+GIF 编码 / 失败原样降级）+"
           "**插件文件夹结构重构**（根目录只留 config.yml / 提示词 / guaipng / img，其余 QUUID/logs/Markdown/cache/ai-context.yml/fake-players.json/stats.yml/openids.yml "
           "自动迁入 `globalconfig/` 子目录——`DataLayout.migrateToGlobalLayout` 在 ConfigManager.initialize 最早阶段幂等迁移，9 处读写点全部改接 globalconfig 路径；"
           "config-version 58 → 59）。\n")

DETAIL_HEAD = "### 1.7.6.3 覆盖轮（注释简化 + 恢复三键 + 表情包缩小 + 文件夹重构；首发轮见下方）\n\n"

DETAIL_BODY = """- **任务1（注释简化，用户口径「我说的简化是注释简化，你理解错了」）**：三端 config.yml 最近新增段（AI 表情包/提示词/网页读取、集群多卡 show-multi-cards、总在线时长源 total-playtime-source、假人列表 show-fake-players、假人白名单 fake-player-whitelist、Fabric 限制超时 restrict-timeout-seconds）注释全部精简——每段标题一两行、每键一行；键与行为零变化。
- **任务2（恢复三键，用户口径「恢复以下删除的键」）**：ai.memes.max-per-reply（默认 1）/ ai.crawler.timeout-seconds（默认 10）/ ai.crawler.max-chars（默认 6000）——QqBindManager 三函数恢复（默认值引用 AiChat.MEME_MAX_PER_REPLY / WebCrawler.DEFAULT_TIMEOUT_SECONDS / DEFAULT_MAX_CHARS 常量），AiChat 能力段与抓取循环接回配置读取，DEFAULT_VALUES 补键，REMOVED_CONFIG_KEYS 撤删（ai.memes.folder 保持删除：文件夹固定 guaipng 不恢复）。
- **任务3（多层爬取上限可配置，用户口径「AI多层网页爬取上限改为可通过配置文件配置，默认最多3页」）**：ai.crawler.max-pages（默认 3）注释明确「多层爬取上限」，多层深入与总读取次数共用此限。
- **任务4（表情包缩小，用户口径「表情包发送直接发送图片形状太大了，不美观，不像表情包，试试能不能缩小，（动图实测是表情包大小）」）**：`AiMemeLibrary.shrinkForSend(bytes)` 纯函数——动图 GIF 字节原样直传（magic number GIF87a/89a 判定，QQ 已按表情尺寸渲染）；静态图（png/jpg/jpeg/webp/bmp）发送前等比缩放到最长边 ≤ 240px（Graphics2D 双线性插值 + 渲染质量提示，透明通道保留）转单帧 GIF（QQ 按 GIF 表情尺寸渲染，观感与动图一致）；ImageIO 读不了（webp 等不支持格式）/ 编码异常原样返回降级（发送不因优化失败而失败）。GroupMessageHandler 发送层接入（读文件字节后先过 shrinkForSend）。
- **任务5（插件文件夹结构重构，用户口径「重构插件文件夹结构（注释官网教程等同步）（已有配置则直接迁移）」）**：新类 `common-Bot manager/DataLayout`（三端共享编译）——根目录只留四样（config.yml 配置文件 / 提示词.md / guaipng 表情包 / img 背景图），其余全部收进 `globalconfig/` 子目录（QUUID 绑定数据含 stats、logs 插件日志、Markdown 回复模板、cache 头像缓存、ai-context.yml、fake-players.json、stats.yml(.migrated)、openids.yml(.migrated)）。`migrateToGlobalLayout` 幂等迁移在 ConfigManager.initialize 最早阶段（一切数据读写之前）执行：目录整名 rename（原子）/ 目标已存在逐项合并同名跳过 / 全新安装无事发生，迁移成功控制台提示。9 处读写点改接 globalconfig：QqBindManager（QUUID/logs/ai-context）+ PluginFileLog（logs/qq）+ KERONG（logs/Bot-%s.log、Markdown 释放）+ ConfigProvider.markdown（Markdown 读取）+ MessageTemplates.directory + InfoCardRenderer.avatarDiskDir（cache/avatars）+ PlayerStatsManager（stats.yml、QUUID/stats）+ FakePlayerRegistry（fake-players.json）+ OpenIdDirectory（openids.yml、QUUID/openid）。教程同步：Markdown 路径两处改 globalconfig + 新增「插件数据目录结构」cfg-item（目录树图示）。
- **任务6（服务器检查更新文件，用户口径「服务器上的检查更新文件没有更新」「简化服务器上的检查更新文件更新内容」）**：version.json changelog 重写为覆盖轮合并口径并简化（每条一行内：①网页读取正式功能 ②表情包缩小 ③文件夹重构 ④注释精简+恢复三键 ⑤cv59）；index.html v1.7.6.3 日志块同步重写。
- **基建**：config-version 58 → 59（恢复三键 + 文件夹迁移 + 注释简化重建）；版本号保持 1.7.6.3 同号覆盖；官网三件与 README 同步。构建坑合规：rm -rf 四模块 build 全量重编 + Fabric remap 缓存删除重打两版 + base=1.7.6.3（官网 JAR）。

"""


def main():
    t = README.read_text(encoding="utf-8")
    assert "### 1.7.6.3 覆盖轮（注释简化" not in t, "已有覆盖轮段（幂等）"

    # 摘要：插在 1.7.6.3 首发摘要行之前
    anchor = "> 1.7.6.3：**AI 网页读取转正式功能**"
    assert anchor in t, "首发摘要锚点未找到"
    t = t.replace(anchor, SUMMARY + anchor, 1)

    # 详史：插在 v1.7.6.3 标题之前
    anchor2 = "### v1.7.6.3 更新日志"
    assert anchor2 in t
    t = t.replace(anchor2, DETAIL_HEAD + DETAIL_BODY + anchor2, 1)

    README.write_text(t, encoding="utf-8")
    print("[OK] README 覆盖轮段完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
