# 🤖 KERONG Penguin

> ⚠️ **此插件是 HuHoBot 的魔改分支**

Minecraft QQ 机器人插件，通过 QQ 官方机器人 API 实现 QQ 群与服务器的深度交互。

## 功能

- **QQ 绑定验证** — 玩家进入服务器前需绑定 QQ，5 位随机绑定码验证
- **AI 对话** — QQ 群 @机器人 或服务器内输入前缀触发，支持 DeepSeek/OpenAI
- **黑名单系统** — 管理员可将 QQ 加入黑名单，自动解绑并踢出
- **快捷指令面板** — QQ 群快捷指令菜单，根据配置动态显示
- **在线查询** — 自定义 .md 模板渲染在线玩家列表（图片自动加时间戳防缓存）
- **消息转发** — QQ 群 ↔ 游戏双向消息转发
- **自定义执行指令** — QQ 群触发自定义服务器命令
- **群内签到** — @机器人 /签到 领取金币奖励（需 Vault，离线暂存自动发放）
- **开发者 API** — 提供 `KERONGPenguinAPI` 入口类，供其他插件调用绑定查询/金币发放等功能

## 开发者 API

其他插件可通过 `KERONGPenguinAPI` 调用本插件功能：

```java
import cn.huohuas001.huhobotPenguin.spigot.api.KERONGPenguinAPI;

KERONGPenguinAPI api = KERONGPenguinAPI.getInstance();
// 通过 QQ 查绑定玩家
String player = api.getBoundPlayer(qqOpenId);
// 给玩家发放金币（Vault）
boolean ok = api.depositCoins(playerName, 100.0);
// 检查玩家是否绑定 QQ
boolean bound = api.isPlayerBound(playerName);
```

## 环境

- Java 17+
- 全面兼容混合端
- QQ 最新版（支持机器人功能）

## 下载

前往 [官网下载页面](https://kerong.xin/penguin/)

## 安装教程

查看 [安装教程](https://kerong.xin/penguin/tutorial.html)

## 版本

当前版本：**1.0.3.7**

## 开源协议

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。
