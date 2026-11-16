#!/usr/bin/env python3
"""1.7.6.3：三端 config.yml AI 段简化重写 + config-version 58 + 版本号六处 → 1.7.6.3。"""
import pathlib
import sys

REPO = pathlib.Path("/home/z/my-project/repo")

OLD_MEMES_CRAWLER = """  # ---------- AI 表情包（默认开） ----------
  # 把图片放进插件目录的 guaipng 文件夹（支持 png / jpg / jpeg / gif /
  # webp / bmp，动态 GIF 正常发送），文件名 = 图片含义；AI 觉得气氛合适
  # 时在回复末尾输出 [表情] 文件名，插件把图片发到群里（每次回复最多
  # max-per-reply 张）；文件增删即时生效，无需重启
  memes:
    enabled: true
    # 表情包文件夹名（插件目录下）
    folder: "guaipng"
    # 每条 AI 回复最多发几张
    max-per-reply: 1
"""

NEW_MEMES = """  # ---------- AI 表情包（默认开） ----------
  # 把图片放进插件目录的 guaipng 文件夹（支持 png / jpg / jpeg / gif /
  # webp / bmp，动态 GIF 正常发送），文件名 = 图片含义；AI 觉得气氛合适
  # 时在回复末尾输出 [表情] 文件名，插件把图片发到群里（每次回复最多
  # 1 张）；文件增删即时生效，无需重启
  memes:
    enabled: true
"""

OLD_CRAWLER = """  # ---------- AI 网页爬取（测试功能，默认关） ----------
  # ⚠ 测试功能警告：开启后 AI 需要先看网页才能回答时可输出 [GET] 网址，
  # 插件抓取网页正文回传给 AI 继续生成；抓取质量与稳定性有限，仅供测试，
  # 生产环境建议保持关闭。仅 http/https 公网页，内网 / 回环地址被拒绝
  # （防 SSRF）；响应体最多读 2MB，仅接受文本类内容
  crawler:
    enabled: false
    # 单次回答最多抓取页数
    max-pages: 2
    # 单页正文提取上限（字符）
    max-chars: 6000
    # 抓取超时秒数
    timeout-seconds: 10
"""

NEW_CRAWLER = """  # ---------- AI 网页读取（正式功能，默认开） ----------
  # 开启后 AI 需要看网页或搜索资料才能回答时，输出 [GET] 网址，插件抓取
  # 网页正文回传给 AI 继续生成；支持多次多层读取（先读搜索页 / 入口页，
  # 再继续读页面里的链接逐层深入），需要搜索时 AI 会用必应（bing.com）。
  # ⚠ 每次读取的网页内容都会消耗额外 token（按 max-pages 页计），接口
  # 按 token 计费时请留意用量；不想要可改回 false。仅 http/https 公网页，
  # 内网 / 回环地址被拒绝（防 SSRF）；响应体最多读 2MB，仅接受文本类内容
  crawler:
    enabled: true
    # 单次回答最多读取页数（多层深入也受此上限约束）
    max-pages: 3
"""


def patch_config(path: pathlib.Path):
    text = path.read_text(encoding="utf-8")
    assert OLD_MEMES_CRAWLER in text, f"memes 旧块未命中: {path}"
    assert OLD_CRAWLER in text, f"crawler 旧块未命中: {path}"
    assert "config-version: 57" in text, f"config-version 57 未命中: {path}"
    text = text.replace(OLD_MEMES_CRAWLER, NEW_MEMES)
    text = text.replace(OLD_CRAWLER, NEW_CRAWLER)
    text = text.replace("config-version: 57", "config-version: 58", 1)
    # 校验：旧键彻底清除（四空格缩进 = AI 段内形态，不误伤 restrict-timeout-seconds）
    for gone in ('    folder: "guaipng"', "    max-per-reply: 1", "    max-chars: 6000",
                 "    timeout-seconds: 10", "测试功能"):
        assert gone not in text, f"残留 {gone}: {path}"
    assert 'enabled: true\n    # 单次回答最多读取页数' in text
    path.write_text(text, encoding="utf-8")
    print(f"[OK] {path}")


def patch_versions():
    targets = [
        ("build.gradle.kts", 'version = "1.7.6.2"', 'version = "1.7.6.3"'),
        ("server-Spigot/src/main/resources/plugin.yml", "version: '1.7.6.2'", "version: '1.7.6.3'"),
        ("server-NeoForge/src/main/resources/META-INF/mods.toml", 'version = "1.7.6.2"', 'version = "1.7.6.3"'),
        ("server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml", 'version = "1.7.6.2"', 'version = "1.7.6.3"'),
        ("server-Fabric/src/main/resources/fabric.mod.json", '"version": "1.7.6.2"', '"version": "1.7.6.3"'),
        ("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt",
         'private const val MOD_VERSION = "1.7.6.1"', 'private const val MOD_VERSION = "1.7.6.3"'),
    ]
    for rel, old, new in targets:
        p = REPO / rel
        t = p.read_text(encoding="utf-8")
        assert old in t, f"版本旧值未命中: {rel}"
        t = t.replace(old, new, 1)
        p.write_text(t, encoding="utf-8")
        print(f"[OK] {rel}")


def main():
    for mod in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        patch_config(REPO / mod / "src/main/resources/config.yml")
    patch_versions()
    print("全部完成")


if __name__ == "__main__":
    sys.exit(main())
