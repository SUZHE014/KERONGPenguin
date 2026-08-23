# KERONGPenguin API 文档

## 目录

1. [引入 SDK](#1-引入-sdk)
2. [QClient API](#2-qclient-api)
3. [HuHoBotSpigot 主类 API](#3-huhobotspigot-主类-api)
4. [NicknameManager 昵称管理](#4-nicknamemanager-昵称管理)
5. [BindingRepository 绑定仓库](#5-bindingrepository-绑定仓库)
6. [BindingInfo 绑定信息](#6-bindinginfo-绑定信息)
7. [QQ 群命令](#7-qq-群命令)
8. [开发者 API（KERONGPenguinAPI）](#8-开发者-apikerongpenguinapi)

---

## 1. 引入 SDK

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

### 获取主插件实例

```java
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import org.bukkit.plugin.Plugin;

Plugin raw = getServer().getPluginManager().getPlugin("KERONGPenguin");
if (raw instanceof HuHoBotSpigot) {
    HuHoBotSpigot bot = (HuHoBotSpigot) raw;
}
```

---

## 2. QClient API

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
| `sendAtToGroups(nickname, content)` | `void` | 向所有群发送艾特消息（通过昵称艾特） |
| `shutdown()` | `void` | 关闭客户端 |

### sendAtToGroups 用法

```java
import cn.huohuas001.bot.QClient;

// 向所有群发送艾特消息（nickname 对应已注册的昵称）
QClient.INSTANCE.sendAtToGroups("张三", "你被点名了");
```

> `nickname` 参数会通过 `NicknameManager` 解析为对应的 OpenId，生成 `<qqbot-at-user id="..." />` 艾特标签。

### 广播消息示例

```java
// 广播游戏消息
QClient.INSTANCE.broadcastGameMessage("Steve", "大家好");

// 向指定群发送 Markdown
QClient.INSTANCE.sendMarkdownToGroup(groupOpenId, "# 标题", null);
```

---

## 3. HuHoBotSpigot 主类 API

`cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot`，实现 `HuHoBot` 接口。

### 消息发送

| 方法 | 说明 |
|------|------|
| `broadcastMessage(text)` | 向所有配置群发送文本 |
| `broadcastMessage(text, mentions)` | 向所有配置群发送文本（带艾特列表） |
| `sendMarkdown(md, keyboard)` | 向所有群发送 Markdown |
| `sendMarkdownToGroup(groupOpenId, md, keyboard)` | 向指定群发送 Markdown |
| `replyMarkdown(event, md, keyboard)` | 回复群消息（Markdown） |
| `replyWithImg(event, text, imgUrl)` | 回复群消息（含图片） |

### 消息格式化

| 方法 | 说明 |
|------|------|
| `auditText(text)` | 文本审核（过滤敏感词） |
| `filterText(text)` | 过滤文本 |
| `formatGroupMessage(senderName, msg)` | 格式化 QQ→游戏消息 |
| `formatGameMessage(playerName, msg)` | 格式化游戏→QQ 消息 |
| `formatPlayerJoinMessage(name)` | 格式化玩家加入消息 |
| `formatPlayerQuitMessage(name)` | 格式化玩家退出消息 |
| `formatPlayerEventMessage(name, event)` | 格式化玩家事件消息 |

### 数据查询

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

### 调度器

| 方法 | 说明 |
|------|------|
| `submit(runnable)` | 同步执行 |
| `submitAsync(runnable)` | 异步执行 |
| `submitLater(delay, runnable)` | 延迟同步执行 |
| `submitTimer(delay, period, runnable)` | 定时同步执行 |

### 命令执行

| 方法 | 说明 |
|------|------|
| `sendCommand(command)` | 发送命令，返回 CompletableFuture |
| `dispatchCommand(command)` | 分发命令 |

```java
Plugin raw = getServer().getPluginManager().getPlugin("KERONGPenguin");
if (raw instanceof HuHoBotSpigot) {
    HuHoBotSpigot bot = (HuHoBotSpigot) raw;
    // 广播消息（带艾特）
    java.util.List<String> mentions = new ArrayList<>();
    mentions.add("张三");
    bot.broadcastMessage("通知内容", mentions);
    // 格式化消息
    String formatted = bot.formatGameMessage("Steve", "Hello");
    // 获取在线列表
    List<String> online = bot.getOnlineList();
}
```

---

## 4. NicknameManager 昵称管理

`cn.huohuas001.bot.NicknameManager`，通过 `NicknameManager.INSTANCE` 访问。

管理 QQ 群昵称 ↔ OpenId 的映射，用于 `sendAtToGroups` 解析艾特目标。

| 方法 | 签名 | 说明 |
|------|------|------|
| `load()` | `void` | 从文件加载昵称映射 |
| `save()` | `void` | 保存昵称映射到文件 |
| `put(nickname, openId)` | `void` | 添加昵称→OpenId 映射 |
| `getOpenId(nickname)` | `String` | 通过昵称查 OpenId |
| `getNickname(openId)` | `String` | 通过 OpenId 查昵称 |
| `matchByPrefix(prefix)` | `List<Pair<String,String>>` | 按前缀匹配昵称（返回 Pair 列表：nickname, openId） |
| `all()` | `List<Pair<String,String>>` | 获取所有映射 |
| `clear()` | `void` | 清空所有映射 |
| `size()` | `int` | 获取映射数量 |

```java
import cn.huohuas001.bot.NicknameManager;

// 添加昵称映射
NicknameManager.INSTANCE.put("张三", "openId_xxx");

// 查询
String openId = NicknameManager.INSTANCE.getOpenId("张三");
String nickname = NicknameManager.INSTANCE.getNickname("openId_xxx");

// 前缀匹配
for (kotlin.Pair<String, String> pair : NicknameManager.INSTANCE.matchByPrefix("张")) {
    System.out.println(pair.getFirst() + " -> " + getSecond());
}
```

---

## 5. BindingRepository 绑定仓库

`cn.huohuas001.bot.state.BindingRepository`

管理群级 QQ 绑定关系（按 groupOpenId 分组）。每个绑定记录是 `BindingInfo`。

| 方法 | 签名 | 说明 |
|------|------|------|
| `getBinding(groupOpenId, openId)` | `BindingInfo?` | 获取指定群中某 OpenId 的绑定信息 |
| `setBinding(groupOpenId, openId, playerName)` | `boolean` | 设置绑定，成功返回 true |
| `removeBinding(groupOpenId, openId)` | `boolean` | 移除绑定，成功返回 true |
| `findByPlayerName(groupOpenId, playerName)` | `Entry<String, BindingInfo>?` | 按玩家名查找绑定 |
| `allInGroup(groupOpenId)` | `Map<String, BindingInfo>` | 获取指定群的所有绑定 |
| `updateSettings(groupOpenId, openId, qqDisplayName, mcDisplayName)` | `boolean` | 更新显示名设置 |
| `allBindings()` | `Map<String, Map<String, BindingInfo>>` | 获取所有群的绑定（外层 key=groupOpenId，内层 key=openId） |
| `replaceAll(bindings)` | `void` | 替换全部绑定数据 |

```java
import cn.huohuas001.bot.state.BindingRepository;
import cn.huohuas001.bot.datapack.BindingInfo;

BindingRepository repo = new BindingRepository(() -> kotlin.Unit.INSTANCE);

// 设置绑定
boolean ok = repo.setBinding(groupOpenId, openId, "Steve");

// 查询绑定
BindingInfo info = repo.getBinding(groupOpenId, openId);
if (info != null) {
    String player = info.getPlayerName();
    String qqDisplay = info.getQqDisplayNameMode();
    String mcDisplay = info.getMcDisplayNameMode();
}

// 移除绑定
repo.removeBinding(groupOpenId, openId);

// 获取群内所有绑定
Map<String, BindingInfo> all = repo.allInGroup(groupOpenId);
```

---

## 6. BindingInfo 绑定信息

`cn.huohuas001.bot.datapack.BindingInfo`

不可变数据类，存储单个绑定记录。

| 字段 | 类型 | 说明 |
|------|------|------|
| `playerName` | `String` | 绑定的游戏玩家名 |
| `qqDisplayNameMode` | `String` | QQ 端显示名模式 |
| `mcDisplayNameMode` | `String` | 游戏端显示名模式 |

访问方法：`getPlayerName()`、`getQqDisplayNameMode()`、`getMcDisplayNameMode()`

```java
BindingInfo info = new BindingInfo("Steve", "default", "default");
String player = info.getPlayerName();        // "Steve"
String qqMode = info.getQqDisplayNameMode();  // "default"
String mcMode = info.getMcDisplayNameMode();  // "default"

// 复制创建
BindingInfo copy = info.copy("Alex", "nickname", "default");
```

---

## 7. QQ 群命令

### 绑定相关命令

| 命令 | 说明 |
|------|------|
| `/绑定 <绑定码>` 或 `/bind <绑定码>` | 绑定 QQ |
| `/unbind` | 解除当前 QQ 绑定 |
| `/setMcDisplayName <名称>` | 设置游戏端显示名 |
| `/setQqDisplayName <名称>` | 设置 QQ 端显示名 |
| `/version` | 查询插件版本 |

### 服务器内命令

| 命令 | 说明 |
|------|------|
| `/huhobot reload` | 重载配置 |
| `/huhobot info` | 查看适配器信息 |
| `/huhobot panel` | 重新同步 QQ 快捷指令面板 |
| `/at <昵称> <消息>` | 向 QQ 群发送艾特消息（通过 NicknameManager 解析昵称） |

### /at 命令用法

```
/at 张三 你好
```

该命令会向所有配置的 QQ 群发送一条艾特"张三"的消息。昵称需已在 `NicknameManager` 中注册。

---

## 8. 开发者 API（KERONGPenguinAPI）

这是 KERONGPenguin 魔改版特有的公开 API 入口类，封装了 QQ 绑定查询、金币发放、签到等功能。

### 包路径

```
cn.huohuas001.huhobotPenguin.spigot.api.KERONGPenguinAPI
```

### 获取实例

```java
import cn.huohuas001.huhobotPenguin.spigot.api.KERONGPenguinAPI;

KERONGPenguinAPI api = KERONGPenguinAPI.getInstance();
```

### 绑定查询

| 方法 | 签名 | 说明 |
|------|------|------|
| `getBoundPlayer(qqOpenId)` | `String` | 通过 QQ OpenId 查找绑定的游戏玩家名，未绑定返回 null |
| `getPlayerQq(playerName)` | `String` | 获取玩家绑定的 QQ OpenId，未绑定返回 null |
| `getPlayerQuuid(playerName)` | `String` | 获取玩家的 QUUID（专属 UUID） |
| `isPlayerBound(playerName)` | `boolean` | 检查玩家是否已绑定 QQ |
| `isBlacklisted(qqOpenId)` | `boolean` | 检查 QQ 是否在黑名单中 |

### 金币操作（Vault）

| 方法 | 签名 | 说明 |
|------|------|------|
| `depositCoins(playerName, amount)` | `boolean` | 给玩家发放金币（通过 Vault），成功返回 true |
| `getPendingCoins(playerName)` | `double` | 获取玩家在 QUUID 中暂存的待领金币（签到等产生的离线奖励） |

### 签到配置

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
