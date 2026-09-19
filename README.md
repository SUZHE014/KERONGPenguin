# 🤖 KERONG Penguin

> ⚠️ **此插件是 HuHoBot 的魔改分支**（源码已按 HuHoBot 上游结构重写为可读的 Kotlin 工程）

Minecraft QQ 机器人插件，通过 QQ 官方机器人 API 实现 QQ 群与服务器的深度交互。

## 功能

- **QQ 绑定验证** — 玩家进入服务器前需绑定 QQ，5 位随机绑定码验证
- **个人信息统计卡片** — QQ 群发送 `/个人信息`，渲染毛玻璃风格 15 项生涯统计卡片（金币/游戏时长/击杀/签到等），支持自定义背景图；`/查信息` 保持原版行为（查询 OpenId，用于配置 bot.groups）
- **玩家统计自记录** — 实时累计游戏时长、击杀、死亡、挖掘、距离等数据，定时异步保存，对性能几乎零影响
- **AI 对话** — QQ 群 @机器人 或服务器内输入前缀触发，支持 DeepSeek/OpenAI
- **黑名单系统** — 管理员可将 QQ 加入黑名单，自动解绑并踢出
- **快捷指令面板** — QQ 群快捷指令菜单，根据配置动态显示
- **在线查询** — 自定义 .md 模板渲染在线玩家列表（图片自动加时间戳防缓存）
- **消息转发** — QQ 群 ↔ 游戏双向消息转发
- **自定义执行指令** — QQ 群触发自定义服务器命令
- **群内签到** — @机器人 /签到 领取金币奖励（需 Vault，离线暂存自动发放）

## 环境

- Java 17+
- Spigot / Paper / 混合端（Mohist、CatServer、Arclight 等，自动检测命令执行器）
- QQ 最新版（支持机器人功能）

## 下载

前往 [官网下载页面](https://kerong.xin/penguin/)

## 安装教程

查看 [安装教程](https://kerong.xin/penguin/tutorial.html)

## 从源码构建

```bash
./gradlew :server-Spigot:jar   # 编译两个模块
python3 scripts/package_jar.py # 合成可分发的 fat JAR（可选）
```

> 依赖的第三方库（kloping QQ SDK、fastjson 等）以 `libs/deps.jar` 形式提供编译期引用，
> 打包时复用原版 JAR 中的依赖字节码。

## 项目结构

```
common-Bot/      # 机器人核心（QQ 客户端、群命令、状态持久化、QQ 绑定管理）
server-Spigot/   # Spigot 平台适配（主类、命令执行器、事件监听、配置管理、卡片渲染）
```

## API 文档

查看 [API 文档](docs/API.md)

## 版本

当前版本：**0.1.5.0（测试版）**

### v0.1.5.0 更新日志

- **修复**：非混合端服务器不再误提示“已启用混合控制台命令执行器”——
  `command-sender` 默认值改为 `Auto`，自动检测混合端（Mohist/CatServer/Arclight 等）
  并选择合适的执行器；配置了 `Hybrid` 但检测到纯 Spigot/Paper 时自动回退并给出提示
- **新增**：QQ 群 `/查信息` 命令 —— 查询当前 QQ 绑定玩家的生涯统计并渲染为图片卡片发送（不 @ 提及），
  未绑定提示“该账号未绑定QQ”
- **新增**：玩家统计数据自记录（游戏时长、今日在线、击杀怪物、屠龙、死亡、钓鱼、挖掘残骸、
  触发袭击、繁殖动物、飞行/行走距离、造成伤害）——实时累计、定时异步保存
- **新增**：查信息卡片背景图支持 —— 将图片放入 `plugins/KERONGPenguin/img/` 目录即可，
  无图片时使用黑色背景
- **重构**：全部源码按 HuHoBot 上游工程结构重写为可读的 Kotlin
  （common-Bot + server-Spigot 双模块），替代原反编译代码

## 开源协议

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。
