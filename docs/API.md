# KERONGPenguin API 文档

> 本文档基于 [HuHoBot 官方文档](http://huhobot.txssb.cn/develop/) 整理，并补充了 KERONGPenguin 魔改版特有的 API。

## 目录

1. [Spigot/Paper 适配器 API](#1-spigotpaper-适配器-api)
2. [公共 API（MsgPack 与事件）](#2-公共-apimsgpack-与事件)
3. [当前可用 API（QClient / HuHoBotSpigot）](#3-当前可用-apiqclient--huhobotspigot)
4. [开发者 API（KERONGPenguinAPI）](#4-开发者-apikerongpenguinapi)

---

## 1. Spigot/Paper 适配器 API

> 参考来源：<http://huhobot.txssb.cn/develop/spigot/>

### 引入 SDK

将 `KERONGPenguin_Spigot-x.y.z.jar` 放到项目的 `libs` 目录：

```gradle
dependencies {
    compileOnly(files("libs/KERONGPenguin_Spigot-x.y.z.jar"))
    compileOnly("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")
}
```

`plugin.yml`：

```yaml
name: MyKERONGPenguinAddon
main: com.example.myaddon.MyKERONGPenguinAddon
version: 1.0.0
api-version: '1.16'
depend:
  - KERONGPenguin
```

> ⚠️ **注意**：魔改版插件名为 `KERONGPenguin`（非 `HuHoBotPenguin`），`depend` 需使用 `KERONGPenguin`。

### 监听 QQ 群消息

事件类：`cn.huohuas001.huhobotPenguin.spigot.events.OnBotRecvMsg`

```java
package com.example.myaddon;

import cn.huohuas001.huhobotPenguin.spigot.events.OnBotRecvMsg;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class BotListener implements Listener {
    @EventHandler
    public void onBotMessage(OnBotRecvMsg event) {
        String user = event.getMessage().getSender().getUsername();
        String content = event.getMessage().getContent();
        if (content.equalsIgnoreCase("/hello")) {
            event.replyText("你好，" + user);
            event.setCancelled(true);
        }
    }
}
```

在 `onEnable` 注册：

```java
getServer().getPluginManager().registerEvents(new BotListener(), this);
```

> 取消事件会阻止后续的默认全量转发。

### 自定义命令事件

事件类：`cn.huohuas001.huhobotPenguin.spigot.events.OnBotCommand`

```java
@EventHandler
public void onBotCommand(OnBotCommand event) {
    if ("hello".equals(event.getMessage().getCommandKey())) {
        String args = event.getMessage().getCommandArguments();
        event.replyText("参数: " + args);
        event.setCancelled(true);
    }
}
```

消息 `/hello world` 对应：
- `commandKey = hello`
- `commandArguments = world`

### 获取主插件实例

```java
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import org.bukkit.plugin.Plugin;

Plugin raw = getServer().getPluginManager().getPlugin("KERONGPenguin");
if (raw instanceof HuHoBotSpigot) {
    HuHoBotSpigot bot = (HuHoBotSpigot) raw;
}
```

### 查询认证 QQ 号

查询指定群中某个 OpenID 已绑定的 QQ 号；如果没有认证，返回 `null`：

```java
String qq = bot.getAuthenticatedQQ(groupOpenId, openId);
if (qq == null) {
    // 当前 OpenID 未认证
} else {
    getLogger().info("已认证 QQ: " + qq);
}
```

方法签名：

```java
String getAuthenticatedQQ(String groupOpenId, String openId);
```

### 注册命令和发送消息

```java
HuHoBotSpigot bot = (HuHoBotSpigot) raw;

// 注册自定义命令
bot.registerBotCommand("hello", "say Hello {params}", 0, true);

// 发送文本消息
bot.sendBotText("发送到配置中的所有 QQ 群");
bot.sendBotText(groupOpenId, "发送到指定 QQ 群");

// 发送 Markdown
bot.sendBotMarkdown("# Markdown");
bot.sendBotMarkdown(groupOpenId, markdown, keyboard);

// 注销命令
bot.unregisterBotCommand("hello");
```

方法说明：

| 方法 | 说明 |
|------|------|
| `registerBotCommand(key, command, permission, pushMenu)` | 注册运行时命令 |
| `unregisterBotCommand(key)` | 移除运行时命令 |
| `sendBotText(text)` | 发送到所有配置群 |
| `sendBotText(groupOpenId, text)` | 发送到指定群，返回 boolean |
| `sendBotMarkdown(md)` | 发送 Markdown 到所有群 |
| `sendBotMarkdown(groupOpenId, md, keyboard)` | 发送 Markdown 到指定群 |

参数说明：
- `permission > 0`：仅管理员可执行
- `pushMenu = true`：同步到 QQ 指令面板

命令模板占位符：
- `{params}` — 完整参数
- `{group}` — 群 ID
- `{user}` — 用户 ID
- `{0}` `{1}` — 按空格拆分后的参数
- `&1` `&2` — 按空格拆分后的参数

### 回复事件

```java
event.reply("普通文本");
event.replyText("普通文本");
event.replyMarkdown("Markdown 内容");
event.replyMarkdown("Markdown 内容", keyboard);
```

> 回复会自动使用原消息的消息 ID 和序号。

### 注意事项

- 事件会在 Bukkit 主线程触发
- 监听器中不要进行同步网络请求或长时间数据库操作
- 必要时使用 Bukkit Scheduler 异步执行

---

## 2. 公共 API（MsgPack 与事件）

> 参考来源：<http://huhobot.txssb.cn/develop/adapter-api/>

所有适配器都提供以下能力：

- 监听 QQ 群消息：`OnBotRecvMsg`
- 监听自定义命令：`OnBotCommand`
- 读取统一的 `MsgPack` 消息快照
- 取消事件，阻止后续默认转发
- 回复触发消息
- 注册和注销运行时自定义命令
- 向所有配置群或指定群发送文本、Markdown

各平台的事件类位于不同包中，但 API 结构保持一致。

### MsgPack 消息快照

`MsgPack` 是不可变消息快照，不直接暴露 QQ SDK 的原始事件对象。

```java
import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack;
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `messageId` | String | QQ 消息 ID |
| `groupOpenId` | String | QQ 群 OpenID，用于回复和指定群发送 |
| `groupId` | String? | QQ 群 ID，可能为空 |
| `sender` | Sender | 消息发送者 |
| `content` | String | 消息文本 |
| `rawContent` | String | 原始消息文本 |
| `timestamp` | String? | 消息时间戳 |
| `messageSequence` | Int | 回复消息所需的消息序号 |
| `commandKey` | String? | 自定义命令键 |
| `commandArguments` | String? | 自定义命令参数 |
| `mentions` | List\<Mention\> | At 用户列表 |
| `attachments` | List\<Attachment\> | 附件列表 |

Java 通过 `getMessageId()`、`getGroupOpenId()`、`getSender()` 等方法访问字段。

### 事件

#### OnBotRecvMsg

在 QQ 消息进入公共命令处理前触发。适合进行消息过滤、审计或自定义回复。

#### OnBotCommand

在消息命中运行时注册的自定义命令时触发。此时 `MsgPack` 中会额外填充 `commandKey` 和 `commandArguments`。

两个事件都支持：

```java
event.replyText("普通文本");
event.replyMarkdown("Markdown 内容");
event.setCancelled(true);
```

> `replyText` 和 `replyMarkdown` 返回 boolean。`true` 表示发送请求已提交，`false` 表示机器人未启动、参数为空或发送失败。

### 查询认证 QQ 号

所有适配器主类都提供同名方法：

```java
// 返回 null 表示未认证
String qq = bot.getAuthenticatedQQ(groupOpenId, openId);
```

### 线程约束

- QQ 消息回调来自 QQ 客户端线程
- Spigot、Nukkit、Allay 会切换到平台服务器线程后触发事件
- Bungee 使用 Bungee 事件总线
- Velocity 等待 EventManager.fire 完成后再读取取消状态

> 监听器中不要执行长时间阻塞操作。网络请求、数据库操作和复杂计算应提交到平台异步调度器。

### 版本

附属插件应使用与服务器中相同版本的适配器 JAR 编译，并使用 `compileOnly`，避免将另一份 HuHoBot 类打包进附属插件。

---

## 3. 当前可用 API（QClient / HuHoBotSpigot）

> ⚠️ **一致性说明**：上述第 1、2 章的适配器 API（`OnBotRecvMsg`/`OnBotCommand`/`MsgPack`/`getAuthenticatedQQ`/`registerBotCommand`/`sendBotText`/`sendBotMarkdown`）属于 HuHoBot 更新版本，**当前魔改版基于 1.2.0.1，尚未包含这些 API**。
>
> 当前 jar 实际可用的 API 如下：

### QClient 单例

`cn.huohuas001.bot.QClient`，通过 `QClient.INSTANCE` 访问。

| 方法 | 签名 | 说明 |
|------|------|------|
| `getStarter()` | `Starter?` | 获取 QQ 客户端 Starter |
| `registerCommand(BaseCommand)` | `void` | 注册命令处理器 |
| `launchClient(appid, secret, logFilePattern)` | `void` | 启动 QQ 客户端 |
| `broadcastGameMessage(playerName, message)` | `void` | 向所有群广播游戏消息 |
| `broadcastPlayerJoin(playerName)` | `void` | 向所有群广播玩家加入 |
| `broadcastPlayerQuit(playerName)` | `void` | 向所有群广播玩家退出 |
| `sendMarkdown(md, keyboard)` | `void` | 向所有群发送 Markdown |
| `sendMarkdownToGroup(groupOpenId, md, keyboard)` | `void` | 向指定群发送 Markdown |
| `replyMarkdown(event, md, keyboard)` | `void` | 回复消息（Markdown） |
| `replyWithImg(event, text, imgUrl)` | `void` | 回复消息（含图片） |
| `shutdown()` | `void` | 关闭客户端 |

```java
import cn.huohuas001.bot.QClient;

// 广播游戏消息
QClient.INSTANCE.broadcastGameMessage("Steve", "大家好");

// 向指定群发送 Markdown
QClient.INSTANCE.sendMarkdownToGroup(groupOpenId, "# 标题", null);
```

### HuHoBotSpigot 主类

`cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot`，实现 `HuHoBot` 接口。

#### 消息发送

| 方法 | 说明 |
|------|------|
| `broadcastMessage(text)` | 向所有配置群发送文本 |
| `sendMarkdown(md, keyboard)` | 向所有群发送 Markdown |
| `sendMarkdownToGroup(groupOpenId, md, keyboard)` | 向指定群发送 Markdown |
| `replyMarkdown(event, md, keyboard)` | 回复群消息（Markdown） |
| `replyWithImg(event, text, imgUrl)` | 回复群消息（含图片） |

#### 消息格式化

| 方法 | 说明 |
|------|------|
| `auditText(text)` | 文本审核（过滤敏感词） |
| `filterText(text)` | 过滤文本 |
| `formatGroupMessage(senderName, msg)` | 格式化 QQ→游戏消息 |
| `formatGameMessage(playerName, msg)` | 格式化 游戏→QQ 消息 |
| `formatPlayerJoinMessage(name)` | 格式化玩家加入消息 |
| `formatPlayerQuitMessage(name)` | 格式化玩家退出消息 |
| `formatPlayerEventMessage(name, event)` | 格式化玩家事件消息 |

#### 数据查询

| 方法 | 说明 |
|------|------|
| `getOnlineList()` | 在线玩家列表 |
| `getBotAppId()` | 机器人 AppID |
| `getBotSecret()` | 机器人密钥 |
| `getServerName()` | 服务器名称 |
| `getServerVersion()` | 服务器版本 |
| `getServerPluginList()` | 服务器插件列表 |
| `getServerCommandHelp(cmd, lang)` | 命令帮助 |
| `getServerLogs(limit, filter)` | 服务器日志 |
| `getChatFormat()` | 聊天格式配置 |
| `getPlayerEventFormat()` | 玩家事件格式 |
| `getMotd()` | Motd 配置 |
| `getMarkdownFiles()` | Markdown 模板映射 |
| `getMarkdown(key)` | 获取指定 Markdown 模板 |
| `getGroupOpenIdList()` | 配置的 QQ 群 OpenId 列表 |
| `getAdminList()` | 管理员列表 |
| `getCommandList()` | 命令开关映射 |
| `getFilterRegexList()` | 过滤正则列表 |
| `getSensitiveWords()` | 敏感词列表 |
| `getAgentConfig()` | Agent 配置 |

#### 调度器

| 方法 | 说明 |
|------|------|
| `submit(runnable)` | 同步执行 |
| `submitAsync(runnable)` | 异步执行 |
| `submitLater(delay, runnable)` | 延迟同步执行 |
| `submitTimer(delay, period, runnable)` | 定时同步执行 |

#### 命令执行

| 方法 | 说明 |
|------|------|
| `sendCommand(command)` | 发送命令，返回 CompletableFuture |
| `dispatchCommand(command)` | 分发命令 |

```java
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;

Plugin raw = getServer().getPluginManager().getPlugin("KERONGPenguin");
if (raw instanceof HuHoBotSpigot) {
    HuHoBotSpigot bot = (HuHoBotSpigot) raw;
    // 广播消息
    bot.broadcastMessage("服务器公告");
    // 格式化消息
    String formatted = bot.formatGameMessage("Steve", "Hello");
    // 获取在线列表
    List<String> online = bot.getOnlineList();
}
```

---

## 4. 开发者 API（KERONGPenguinAPI）

> 这是 KERONGPenguin 魔改版特有的公开 API 入口类，封装了 QQ 绑定查询、金币发放、签到等功能。

### 包路径

```
cn.huohuas001.huhobotPenguin.spigot.api.KERONGPenguinAPI
```

### 获取实例

```java
import cn.huohuas001.huhobotPenguin.spigot.api.KERONGPenguinAPI;

KERONGPenguinAPI api = KERONGPenguinAPI.getInstance();
```

### API 方法

#### 绑定查询

| 方法 | 签名 | 说明 |
|------|------|------|
| `getBoundPlayer(qqOpenId)` | `String` | 通过 QQ OpenId 查找绑定的游戏玩家名，未绑定返回 null |
| `getPlayerQq(playerName)` | `String` | 获取玩家绑定的 QQ OpenId，未绑定返回 null |
| `getPlayerQuuid(playerName)` | `String` | 获取玩家的 QUUID（专属 UUID） |
| `isPlayerBound(playerName)` | `boolean` | 检查玩家是否已绑定 QQ |
| `isBlacklisted(qqOpenId)` | `boolean` | 检查 QQ 是否在黑名单中 |

#### 金币操作（Vault）

| 方法 | 签名 | 说明 |
|------|------|------|
| `depositCoins(playerName, amount)` | `boolean` | 给玩家发放金币（通过 Vault），成功返回 true |
| `getPendingCoins(playerName)` | `double` | 获取玩家在 QUUID 中暂存的待领金币（签到等产生的离线奖励） |

#### 签到配置

| 方法 | 签名 | 说明 |
|------|------|------|
| `isCheckinEnabled()` | `boolean` | 检查签到功能是否开启 |
| `getCheckinReward()` | `double` | 获取签到奖励金币数量 |

### 使用示例

```java
import cn.huohuas001.huhobotPenguin.spigot.api.KERONGPenguinAPI;

public class MyAddon extends JavaPlugin {
    @Override
    public void onEnable() {
        KERONGPenguinAPI api = KERONGPenguinAPI.getInstance();

        // 1. 通过 QQ 查绑定玩家
        String player = api.getBoundPlayer("xxxxxxxx_openid");
        if (player != null) {
            getLogger().info("绑定的玩家: " + player);

            // 2. 检查是否绑定
            boolean bound = api.isPlayerBound(player);
            getLogger().info("已绑定: " + bound);

            // 3. 给玩家发放金币（需 Vault）
            boolean ok = api.depositCoins(player, 100.0);
            getLogger().info("金币发放: " + (ok ? "成功" : "失败"));

            // 4. 查询待领签到金币
            double pending = api.getPendingCoins(player);
            getLogger().info("待领金币: " + pending);
        }

        // 5. 检查签到功能状态
        if (api.isCheckinEnabled()) {
            double reward = api.getCheckinReward();
            getLogger().info("签到奖励: " + reward + " 金币");
        }
    }
}
```

### 依赖

- `KERONGPenguin` 插件（`plugin.yml` 中添加 `depend: [KERONGPenguin]`）
- `Vault` 经济插件（仅 `depositCoins` 方法需要）

### 注意事项

- 所有方法都会检查插件是否就绪，未就绪时返回 null/false/0
- `depositCoins` 使用反射获取 Vault Economy，无需硬依赖 Vault
- `getPendingCoins` 读取的是 QUUID yml 中暂存的离线签到奖励
- 所有方法线程安全，可在异步线程调用
