#!/usr/bin/env python3
"""1.7.6.3 官网 index.html 幂等更新：主下载框 + 历史表置顶 4 行 + v1.7.6.3 日志块。"""
import pathlib
import sys

INDEX = pathlib.Path("/home/z/my-project/repo/website/index.html")

DATE = "2026-11-16"

BLOCK = """<div class="version"><h3>v1.7.6.3</h3><span class="date">2026-11-16</span><ul>
<li>调整：AI 网页读取转为<b>正式功能</b>，默认开启（原测试期默认关闭，升级自动转为开启，不需要可在 ai.crawler.enabled 改回 false）；支持<b>多次多层读取</b>——AI 可先读搜索页 / 入口页，再继续读页面里的链接逐层深入，一次回答最多读 ai.crawler.max-pages（默认 3）页；需要搜索资料时 AI 会用<b>必应（bing.com）</b>搜索引擎，中文关键词自动编码</li>
<li>提醒：AI 网页读取<b>会消耗额外 token</b>（每次读取的网页内容都计入对话，按 max-pages 页计），接口按 token 计费时请留意用量——配置注释与教程均已标明</li>
<li>修复：升级后插件目录仍出现旧知识库文件夹的问题——旧版生成的 knowledge 文件夹新版不再创建、不再读取，启动时自动清理（空文件夹 / 只剩自动生成的说明文件直接删除；有自己放的文件则保留，提示整理进提示词文件后手动删除）</li>
<li>调整：简化 AI 配置，删除四个调优键（升级自动删，行为固定为原默认值）——ai.memes.folder（表情包文件夹固定 guaipng，自定义过的启动时提示移入）、ai.memes.max-per-reply（每条回复最多 1 张）、ai.crawler.timeout-seconds（超时固定 10 秒）、ai.crawler.max-chars（单页正文固定 6000 字符）；AI 段保留 ai.memes.enabled / ai.prompt-file / ai.crawler.enabled / ai.crawler.max-pages 四键</li>
<li>config-version 57 → 58（升级自动删废弃键并重建注释，已改值不动）</li>
</ul></div>
"""

ROWS = """<tr><td>NeoForge 1.7.6.3</td><td>2026-11-16</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.6.3.jar">下载</a></td></tr>
<tr><td>Spigot 1.7.6.3</td><td>2026-11-16</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.6.3.jar">下载</a></td></tr>
<tr><td>Fabric 1.7.6.3（1.20.4 通用包）</td><td>2026-11-16</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.6.3.jar">下载</a></td></tr>
<tr><td>Fabric 1.7.6.3（1.20.1 独立包）</td><td>2026-11-16</td><td><a href="https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.6.3.jar">下载</a></td></tr>
"""


def main():
    t = INDEX.read_text(encoding="utf-8")

    if "v1.7.6.3" in t:
        print("[SKIP] index.html 已含 v1.7.6.3（幂等）")
        return 0

    # ① 主下载框：版本号 + 日期 + 四按钮 JAR 名（dl-btn 上下文锚定，防误伤历史行）
    assert "版本 1.7.6.2" in t
    t = t.replace("版本 1.7.6.2", "版本 1.7.6.3", 1)
    t = t.replace(
        '<p style="color:#7a848e;margin-bottom:20px">发布于 2026-11-15</p>',
        f'<p style="color:#7a848e;margin-bottom:20px">发布于 {DATE}</p>', 1)
    for old, new in [
        ('KERONGPenguin_Spigot-1.7.6.2.jar" class="dl-btn"', 'KERONGPenguin_Spigot-1.7.6.3.jar" class="dl-btn"'),
        ('KERONGPenguin_NeoForge-1.20.4-1.7.6.2.jar" class="dl-btn"', 'KERONGPenguin_NeoForge-1.20.4-1.7.6.3.jar" class="dl-btn"'),
        ('KERONGPenguin_Fabric-1.20.4-1.7.6.2.jar" class="dl-btn"', 'KERONGPenguin_Fabric-1.20.4-1.7.6.3.jar" class="dl-btn"'),
        ('KERONGPenguin_Fabric-1.20.1-1.7.6.2.jar" class="dl-btn"', 'KERONGPenguin_Fabric-1.20.1-1.7.6.3.jar" class="dl-btn"'),
    ]:
        assert old in t, f"按钮锚点未命中: {old}"
        t = t.replace(old, new, 1)

    # ② 历史表置顶 4 行（插在表头行之后、1.7.6.2 行之前）
    anchor = '<tr><td>NeoForge 1.7.6.2</td>'
    assert anchor in t
    t = t.replace(anchor, ROWS + anchor, 1)

    # ③ 更新日志块置顶（插在 changangelog 容器开标签后）
    log_anchor = '<div class="changelog">\n'
    assert log_anchor in t
    t = t.replace(log_anchor, log_anchor + BLOCK, 1)

    # 后验
    assert t.count("1.7.6.3.jar") == 8, f"1.7.6.3.jar 出现 {t.count('1.7.6.3.jar')} 次（应 8：按钮4+历史4）"
    assert t.count("v1.7.6.3") == 1
    assert "版本 1.7.6.3" in t
    INDEX.write_text(t, encoding="utf-8")
    print("[OK] index.html v1.7.6.3 更新完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
