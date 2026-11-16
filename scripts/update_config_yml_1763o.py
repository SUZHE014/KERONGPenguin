#!/usr/bin/env python3
"""1.7.6.3 覆盖轮：三端 config.yml 注释简化（最近新增段）+ 恢复三键 + cv59。"""
import pathlib
import sys

REPO = pathlib.Path("/home/z/my-project/repo")

# ---------- AI 段整块替换（注释简化 + 恢复三键） ----------
OLD_AI = """  # ---------- AI 表情包（默认开） ----------
  # 把图片放进插件目录的 guaipng 文件夹（支持 png / jpg / jpeg / gif /
  # webp / bmp，动态 GIF 正常发送），文件名 = 图片含义；AI 觉得气氛合适
  # 时在回复末尾输出 [表情] 文件名，插件把图片发到群里（每次回复最多
  # 1 张）；文件增删即时生效，无需重启
  memes:
    enabled: true
  # ---------- AI 提示词（单文件，角色设定与专属知识都写在这） ----------
  # 机器人的提示词文件（插件目录下，默认 提示词.md，首次启动自动生成
  # 带说明结构的默认文件）：「角色设定」写身份与回复风格，「专属知识」
  # 写服务器规则、玩法说明、常见问题等资料，AI 对话时都会参考；改完
  # 保存下次对话自动生效，无需重启（角色设定原为配置项 ai.system-prompt，
  # 已并入该文件，升级自动迁移）
  prompt-file: "提示词.md"
  # ---------- AI 网页读取（正式功能，默认开） ----------
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

NEW_AI = """  # ---------- AI 表情包（默认开） ----------
  # 图片放插件目录 guaipng 文件夹（支持 png/jpg/jpeg/gif/webp/bmp），
  # 文件名 = 图片含义；发送时自动缩到表情大小，文件增删即时生效
  memes:
    enabled: true
    # 每条回复最多发几张
    max-per-reply: 1
  # ---------- AI 提示词 ----------
  # 角色设定与专属知识都写在这个文件里，改完保存下次对话生效
  prompt-file: "提示词.md"
  # ---------- AI 网页读取（正式功能，默认开） ----------
  # AI 需要时自动读网页（可多层深入），搜索走必应（bing.com）；
  # ⚠ 每次读取的网页内容都会消耗额外 token（按 max-pages 页计）
  crawler:
    enabled: true
    # 单次回答最多读取页数（多层爬取上限）
    max-pages: 3
    # 单页正文提取上限（字符）
    max-chars: 6000
    # 读取超时秒数
    timeout-seconds: 10
"""

# ---------- 其他最近新增段注释精简 ----------
SIMPLIFY = [
    # show-multi-cards（1.7.6.2）
    ("""    # 查在线 / 查性能是否同时显示多个服务器（默认关）。
    # 开启后这两条命令（含别名 /在线）不再弹「选择服务器」按钮、无视群已
    # 选择——每台服务器各自渲染本服状态卡片（卡片自带本服名），群里同时
    # 显示全部服务器的在线 / 性能状态；关闭时维持旧行为（弹选择按钮 →
    # 单服卡片）。reload 即时生效
    show-multi-cards: false""",
     """    # 查在线 / 查性能同时显示全部服务器（默认关，仅集群生效）；reload 即时生效
    show-multi-cards: false"""),
    # total-playtime-source（1.7.6）
    ("""  # 总在线时长获取源（默认 save-data = 服务器存档数据）：读主世界 stats
  # 统计文件，装插件前就在玩的服务器推荐——玩家名片读不到自动回退
  # 变量 → 插件统计；在线排行榜直接枚举服务器全部玩家（不需要开 QQ
  # 绑定，同名多账号各占一行）；plugin = 插件统计；variable = 变量优先；
  # 三个源的展示格式统一「X时X分」；也接受中文值：存档数据/插件统计/变量
  total-playtime-source: "save-data\"""",
     """  # 总在线时长获取源：save-data = 服务器存档（默认，读 stats 统计文件）/
  # plugin = 插件统计 / variable = 变量优先；也接受中文值：存档数据/插件统计/变量
  total-playtime-source: "save-data\""""),
    # show-fake-players（1.7.6.1）
    ("""  # 默认关。开启后 /查在线 的主列表只显示真实玩家，底部额外显示
  # 「假人在线」小节，列出当前在线的假人插件假人（判定口径：登录连接
  # 为空 / 回环 / 内存连接，或上方 qq-bind.fake-player-whitelist 名单
  # 命中）；同口径的假人也不再计入在线排行榜（含下线假人的历史时长，
  # 登记在插件数据目录 fake-players.json，可手动编辑）。
  # 关闭时全部玩家混排显示，与旧版一致。reload 即时生效
  show-fake-players: false""",
     """  # 查在线额外显示「假人在线」小节（默认关）：开启后主列表只算真实玩家，
  # 底部列出在线假人；同口径假人不计入排行榜。reload 即时生效
  show-fake-players: false"""),
    # fake-player-whitelist（1.7.6）
    ("""  # 假人白名单：列表里的玩家名视为假人插件的假人——Fabric 版跳过
  # 未绑定限制（内存 / 回环连接会自动豁免，检测不到的手动填名字，
  # 大小写不敏感）；所有端共用本名单识别假人：不再计入
  # 在线排行榜、/查在线 开启假人列表后划入假人小节。
  # 留空 = 仅自动检测
  fake-player-whitelist: []""",
     """  # 假人白名单：名单里的玩家名按假人处理（大小写不敏感，不计入排行榜）；
  # 留空 = 仅自动检测（内存 / 回环连接）
  fake-player-whitelist: []"""),
    # restrict-timeout-seconds（1.7.5.9）
    ("""  # （锁定移动、禁方块/生物交互、禁发消息与命令），聊天栏持续输出
  # 绑定码，超过这里的秒数仍未绑定就踢出；再次进服会换新的绑定码，
  # 在群里绑定成功后立即解除全部限制。0 或负数按默认值处理
  # （Spigot / NeoForge 版为登录期拦截，无此限制阶段）
  restrict-timeout-seconds: 120""",
     """  # 未绑定玩家的限制超时踢出秒数（Fabric 版；Spigot/NeoForge 为登录期拦截无此阶段）
  restrict-timeout-seconds: 120"""),
]


def patch(path: pathlib.Path):
    text = path.read_text(encoding="utf-8")
    assert OLD_AI in text, f"AI 段旧块未命中: {path}"
    text = text.replace(OLD_AI, NEW_AI, 1)
    for old, new in SIMPLIFY:
        if old not in text:
            print(f"[WARN] {path.name}: 段落未命中（可能已简化或结构差异），跳过：{old.splitlines()[0][:40]}")
            continue
        text = text.replace(old, new, 1)
    assert "config-version: 58" in text, f"cv58 未命中: {path}"
    text = text.replace("config-version: 58", "config-version: 59", 1)
    # 校验
    # 校验（四空格缩进 = AI 段内形态，不误伤 restrict-timeout-seconds）
    assert "    max-per-reply: 1" in text and "    max-chars: 6000" in text and "    timeout-seconds: 10" in text
    assert text.count("    max-per-reply: 1") == 1 and text.count("    max-chars: 6000") == 1 and text.count("    timeout-seconds: 10") == 1
    path.write_text(text, encoding="utf-8")
    print(f"[OK] {path}")


def main():
    for mod in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        patch(REPO / mod / "src/main/resources/config.yml")
    print("三端 config.yml 完成")


if __name__ == "__main__":
    sys.exit(main())
