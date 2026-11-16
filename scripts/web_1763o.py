#!/usr/bin/env python3
"""1.7.6.3 覆盖轮：index.html 的 v1.7.6.3 日志块重写为合并口径（简化）。"""
import pathlib
import sys

INDEX = pathlib.Path("/home/z/my-project/repo/website/index.html")

OLD_BLOCK_START = '<div class="version"><h3>v1.7.6.3</h3>'
NEW_BLOCK = """<div class="version"><h3>v1.7.6.3</h3><span class="date">2026-11-16</span><ul>
<li>新增：AI 网页读取<b>正式功能</b>（默认开）——AI 需要时自动读网页，<b>支持多层深入</b>（先读搜索页/入口页，再继续读页面里的链接），需要搜索时用<b>必应（bing.com）</b>，中文关键词自动编码；网页读取失败时用自然语言告知（不再输出错误码）；⚠ 每次读取消耗额外 token（按 max-pages 页计）</li>
<li>新增：AI 表情包（guaipng 文件夹，文件名 = 图片含义，动图 GIF 正常发送）；<b>静态图发送前自动缩到表情大小</b>（约 240px 转 GIF，QQ 按表情尺寸渲染，与动图表情一样大）</li>
<li>新增：AI 提示词单文件（提示词.md，角色设定 + 专属知识都写在这，改完保存下次对话生效）</li>
<li>调整：<b>重构插件文件夹结构</b>——根目录只留 config.yml / 提示词 / guaipng 表情包 / img 背景图，其余（QQ 绑定数据、日志、回复模板、缓存）自动迁入 <b>globalconfig/</b> 文件夹（升级自动迁移，无需手动搬运）</li>
<li>调整：配置注释精简；恢复 ai.memes.max-per-reply / ai.crawler.timeout-seconds / ai.crawler.max-chars 三键，多层爬取上限 ai.crawler.max-pages（默认 3）可配置；config-version 57 → 59</li>
</ul></div>
"""


def main():
    t = INDEX.read_text(encoding="utf-8")
    start = t.find(OLD_BLOCK_START)
    assert start >= 0, "v1.7.6.3 块未找到"
    end = t.find("</ul></div>", start)
    assert end > 0, "块结尾未找到"
    end += len("</ul></div>")
    old = t[start:end]
    t = t[:start] + NEW_BLOCK + t[end:]
    # 后验：新块在位且只一个 v1.7.6.3 块
    assert t.count('<div class="version"><h3>v1.7.6.3</h3>') == 1
    assert "globalconfig" in t and "表情大小" in t
    INDEX.write_text(t, encoding="utf-8")
    print("[OK] index.html v1.7.6.3 块重写（覆盖轮合并口径）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
