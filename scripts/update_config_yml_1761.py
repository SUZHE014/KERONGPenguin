#!/usr/bin/env python3
"""1.7.6.1：三端 config.yml 模板同步更新（版本 55 + online-list 新键 + 假人白名单注释）。"""
import sys

FILES = [
    "server-Spigot/src/main/resources/config.yml",
    "server-NeoForge/src/main/resources/config.yml",
    "server-Fabric/src/main/resources/config.yml",
]

OLD_WL_COMMENT = """  # 【Fabric 版】假人白名单：列表里的玩家名跳过未绑定限制（假人插件的
  # 内存 / 回环连接会自动豁免，检测不到的手动填名字，大小写不敏感；
  # 留空 = 仅自动检测；Spigot / NeoForge 版无此阶段，本键不生效）
  fake-player-whitelist: []"""

NEW_WL_COMMENT = """  # 假人白名单：列表里的玩家名视为假人插件的假人——Fabric 版跳过
  # 未绑定限制（内存 / 回环连接会自动豁免，检测不到的手动填名字，
  # 大小写不敏感）；1.7.6.1 起所有端共用本名单识别假人：不再计入
  # 在线排行榜、/查在线 开启假人列表后划入假人小节。
  # 留空 = 仅自动检测
  fake-player-whitelist: []"""

OLD_LB_TAIL = """  # 是否用内置背景图（false = 用 img/ 随机图，无图自动回退内置）
  builtin-background: true

# /财富榜的显示人数与背景（单页上限共用上方 leaderboard.page-size）"""

NEW_LB_TAIL = """  # 是否用内置背景图（false = 用 img/ 随机图，无图自动回退内置）
  builtin-background: true

# /查在线 的假人列表（1.7.6.1）
online-list:
  # 默认关。开启后 /查在线 的主列表只显示真实玩家，底部额外显示
  # 「假人在线」小节，列出当前在线的假人插件假人（判定口径：登录连接
  # 为空 / 回环 / 内存连接，或上方 qq-bind.fake-player-whitelist 名单
  # 命中）；同口径的假人也不再计入在线排行榜（含下线假人的历史时长，
  # 登记在插件数据目录 fake-players.json，可手动编辑）。
  # 关闭时全部玩家混排显示，与旧版一致。reload 即时生效
  show-fake-players: false

# /财富榜的显示人数与背景（单页上限共用上方 leaderboard.page-size）"""


def main():
    for path in FILES:
        with open(path, encoding="utf-8") as f:
            text = f.read()
        orig = text
        # 1. config-version 54 -> 55
        if "config-version: 54" not in text:
            print(f"[FAIL] {path}: 找不到 config-version: 54")
            sys.exit(1)
        text = text.replace("config-version: 54", "config-version: 55", 1)
        # 2. 假人白名单注释更新
        if OLD_WL_COMMENT not in text:
            print(f"[FAIL] {path}: 找不到旧假人白名单注释块")
            sys.exit(1)
        text = text.replace(OLD_WL_COMMENT, NEW_WL_COMMENT, 1)
        # 3. online-list 新键（插在 leaderboard 段尾、coins-leaderboard 之前）
        if OLD_LB_TAIL not in text:
            print(f"[FAIL] {path}: 找不到 leaderboard 段尾锚点")
            sys.exit(1)
        text = text.replace(OLD_LB_TAIL, NEW_LB_TAIL, 1)
        if text == orig:
            print(f"[FAIL] {path}: 无变化")
            sys.exit(1)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"[OK] {path} 已更新（版本 55 + online-list.show-fake-players + 白名单注释）")


if __name__ == "__main__":
    main()
