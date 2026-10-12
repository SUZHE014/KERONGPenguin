import cn.huohuas001.huhobotPenguin.spigot.qqbind.EconomyName;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.MessageTemplates;
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager;
import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets;
import cn.huohuas001.huhobotPenguin.spigot.render.LeaderboardRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 1.6.0 功能冒烟（独立 JVM，无头 Java2D，类路径 = NeoForge 分发 JAR：
 * 含 org.bukkit 兼容层 + shaded snakeyaml + 全部模板资源）。
 *
 * 覆盖：
 *  1. 消息模板系统：67 个模板 ID；bundled 默认内容与旧版硬编码一字不差；
 *     占位符替换；help 模板（{version}/{leaderboardLine}）；motd-status 条件行；
 *  2. 经济别名：默认（未配置）时原样返回「金币」；
 *  3. 配置迁移 16→17：qq-bind.render 与 qq-bind.leaderboard 旧键平移至顶层
 *     （自定义值保留、旧段清除），新键补齐（cooldown/金币榜/经济别名/messages/命令）；
 *  4. 金币榜渲染：LeaderboardRenderer 泛化（valueText/title/listHeader）出图，
 *     分段（25 条 → 2 页），背景接入，视觉特征（标题区/行容器）；
 *  5. 新类全量加载冒烟（MessageTemplates/EconomyName/RenderSettings/
 *     CoinsLeaderboardCommands/GroupMessageHandler/MenuManager/FoliaDelegate 等）。
 */
public class Test160 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  [" + detail + "]"); }
    }

    // ---------- 兼容层 JavaPlugin 测试桩（模组侧构造 ConfigManager 用） ----------

    public static class TestPlugin extends org.bukkit.plugin.java.JavaPlugin {
        private final File folder;
        public TestPlugin(File folder) { this.folder = folder; }
        @Override public String getName() { return "KERONGPenguin"; }
        @Override public File getDataFolder() { return folder; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. 消息模板系统 ==");
        List<String> ids = MessageTemplates.INSTANCE.getTEMPLATE_IDS();
        check("模板 ID 总数 = 67", ids.size() == 67, "实际 " + ids.size());
        check("含 1.6.0 新模板 coins-leaderboard-no-economy",
            ids.contains("coins-leaderboard-no-economy"), "列表缺失");
        check("含 bind-usage-fallback", ids.contains("bind-usage-fallback"), "列表缺失");

        // bundled 默认内容（与 1.5.7.3 硬编码一字不差）
        String[][] exact = {
            {"bind-success", "✅ QQ 绑定成功！现在可以重新进入服务器了。"},
            {"bind-invalid", "❌ 绑定码无效或已过期。\n请重新进入服务器获取新的绑定码后再试。"},
            {"bind-usage", "📋 QQ 绑定\n用法: /绑定 <绑定码>\n请先进入服务器获取专属绑定码。"},
            {"query-too-frequent", "查询太频繁了，请稍后再试"},
            {"server-busy", "当前查询人数较多，请稍后再试"},
            {"image-send-failed", "❌ 图片发送失败（机器人连接可能断开），请稍后重试"},
            {"manager-not-ready", "❌ 管理器未就绪"},
            {"no-admin-permission", "你没有执行此命令的管理员权限"},
            {"command-disabled", "此命令已被管理员关闭"},
            {"forward-disabled", "群聊转发功能已关闭"},
            {"ai-context-on", "✅ AI 对话上下文已全局开启，将保留最近对话历史。"},
            {"rebind-not-bound", "ℹ️ 你的 QQ 未绑定任何游戏账号。"},
            {"checkin-already", "{prefix}  ❌签到失败，💰你太贪了\n今日已经签到过了，请明天再试..."},
            {"motd-usage", "用法: /motd <服务器地址>\n示例: /motd mc.hypixel.net"},
        };
        for (String[] pair : exact) {
            String rendered = MessageTemplates.INSTANCE.render(pair[0], Map.of());
            check("模板内容 " + pair[0], pair[1].equals(rendered), "渲染为 " + quote(rendered));
        }

        // 占位符替换
        String kick = MessageTemplates.INSTANCE.render("blacklist-added-kicked",
            Map.of("name", "张三", "player", "Steve"));
        check("占位符替换 blacklist-added-kicked",
            kick.equals("✅ 已将 张三 加入黑名单。\n已解绑并踢出玩家: Steve"), "渲染为 " + quote(kick));

        String info = MessageTemplates.INSTANCE.render("info-target-not-bound", Map.of("name", "李四"));
        check("占位符替换 info-target-not-bound",
            info.equals("@李四 未绑定游戏账号（该 QQ 还未完成绑定，无法查询其玩家信息）"), "渲染为 " + quote(info));

        String openid = MessageTemplates.INSTANCE.render("openid-lookup", Map.of(
            "openid", "OP123", "nickname", "王五", "lastSeenPart", "", "player", "未绑定任何游戏玩家"));
        check("占位符替换 openid-lookup（无 lastSeen 行）",
            openid.equals("【OpenID 反查】\nOpenId: OP123\n群昵称: 王五\n绑定玩家: 未绑定任何游戏玩家"),
            "渲染为 " + quote(openid));
        String openid2 = MessageTemplates.INSTANCE.render("openid-lookup", Map.of(
            "openid", "OP123", "nickname", "王五", "lastSeenPart", "\n最后活跃: 2026-10-12 15:00", "player", "Steve"));
        check("占位符替换 openid-lookup（含 lastSeen 行）",
            openid2.contains("最后活跃: 2026-10-12 15:00") && openid2.contains("绑定玩家: Steve"),
            "渲染为 " + quote(openid2));

        // help 模板（{version} + {leaderboardLine}）
        String help = MessageTemplates.INSTANCE.render("help", Map.of(
            "version", "1.6.0", "leaderboardLine", "- /在线排行榜 —— 总在线时长排行\n"));
        check("help 模板版本与金币榜条目",
            help.contains("**当前插件版本：v1.6.0**")
                && help.contains("- /金币榜 —— 金币数量排行图片")
                && help.contains("- /查在线 —— 查询在线玩家（可简写 /在线）")
                && help.contains("- /在线排行榜 —— 总在线时长排行"),
            "缺失关键行");
        String helpNoLb = MessageTemplates.INSTANCE.render("help", Map.of(
            "version", "1.6.0", "leaderboardLine", ""));
        check("help 模板 leaderboardLine 为空时条目消失",
            !helpNoLb.contains("/在线排行榜"), "排行榜条目仍在");

        // motd-status 条件行
        String motd = MessageTemplates.INSTANCE.render("motd-status", Map.of(
            "type", "Java", "motd", "A Minecraft Server", "version", "1.21.1",
            "protocolLine", "协议: 767\n", "online", "12", "max", "40",
            "delay", "20", "playersSection", "\n**在线玩家:**\n- Alice\n- Bob\n"));
        check("motd-status 模板（协议行 + 玩家小节）",
            motd.startsWith("**MC 服务器状态查询**\n\n类型: Java\n")
                && motd.contains("协议: 767\n在线人数: 12/40\n延迟: 20ms\n\n**在线玩家:**\n- Alice\n- Bob\n"),
            "渲染为 " + quote(motd));

        // 未知模板 → 空串
        check("未知模板 ID 渲染为空串",
            MessageTemplates.INSTANCE.render("no-such-template", Map.of()).isEmpty(), "非空");

        System.out.println("\n== 2. 经济别名（默认态） ==");
        check("默认经济名称 = 金币", "金币".equals(EconomyName.INSTANCE.current()), "实际 " + EconomyName.INSTANCE.current());
        check("未配置别名时文本原样返回",
            EconomyName.INSTANCE.apply("玩家 获得了100金币").equals("玩家 获得了100金币"), "被改写");

        System.out.println("\n== 3. 配置迁移 16→17 ==");
        Path tmp = Files.createTempDirectory("penguin160");
        File folder = tmp.toFile();
        // 构造 1.5.7.3 形态的旧配置（自定义值：gc=false / top=30 / 排行榜背景=false）
        String oldConfig = String.join("\n",
            "config-version: 16",
            "serverName: \"OldServer\"",
            "bot:",
            "  app-id: \"\"",
            "  secret: \"\"",
            "  name: \"OldBot\"",
            "  groups: []",
            "qq-bind:",
            "  enabled: true",
            "  personal-info: true",
            "  render:",
            "    gc-after-render: false",
            "    result-cache-seconds: 120",
            "    info-card-builtin-background: false",
            "    online-list-builtin-background: true",
            "  leaderboard:",
            "    top: 30",
            "    builtin-background: false",
            "commands:",
            "  帮助: true",
            "  查在线: true",
            "  在线排行榜: true",
            "");
        Files.write(folder.toPath().resolve("config.yml"), oldConfig.getBytes(StandardCharsets.UTF_8));

        TestPlugin plugin = new TestPlugin(folder);
        ConfigManager manager = new ConfigManager(plugin);
        manager.initialize();

        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
        check("config-version 升至 17", cfg.getInt("config-version") == 17, "实际 " + cfg.getInt("config-version"));
        check("迁移 render.gc-after-render 自定义值保留（false）",
            cfg.getBoolean("render.gc-after-render") == false, "实际 " + cfg.getBoolean("render.gc-after-render"));
        check("迁移 render.result-cache-seconds 自定义值保留（120）",
            cfg.getInt("render.result-cache-seconds") == 120, "实际 " + cfg.getInt("render.result-cache-seconds"));
        check("迁移 render.info-card-builtin-background 自定义值保留（false）",
            cfg.getBoolean("render.info-card-builtin-background") == false, "实际 " + cfg.getBoolean("render.info-card-builtin-background"));
        check("迁移 leaderboard.top 自定义值保留（30）",
            cfg.getInt("leaderboard.top") == 30, "实际 " + cfg.getInt("leaderboard.top"));
        check("迁移 leaderboard.builtin-background 自定义值保留（false）",
            cfg.getBoolean("leaderboard.builtin-background") == false, "实际 " + cfg.getBoolean("leaderboard.builtin-background"));
        check("旧段 qq-bind.render 已清除", !cfg.isSet("qq-bind.render"), "仍存在");
        check("旧段 qq-bind.leaderboard 已清除", !cfg.isSet("qq-bind.leaderboard"), "仍存在");
        check("qq-bind.enabled 保留（true）", cfg.getBoolean("qq-bind.enabled"), "丢失");
        check("新键 render.command-cooldown-seconds 补齐（默认 5）",
            cfg.getInt("render.command-cooldown-seconds") == 5, "实际 " + cfg.getInt("render.command-cooldown-seconds"));
        check("新键 coins-leaderboard.top 补齐（默认 15）",
            cfg.getInt("coins-leaderboard.top") == 15, "实际 " + cfg.getInt("coins-leaderboard.top"));
        check("新键 economy-alias 补齐（空串）",
            "".equals(cfg.getString("economy-alias", null)), "实际 " + cfg.getString("economy-alias"));
        check("新键 messages.bind-success 补齐",
            "bind-success.md".equals(cfg.getString("messages.bind-success")), "实际 " + cfg.getString("messages.bind-success"));
        check("新键 messages.motd-status 补齐",
            "motd-status.md".equals(cfg.getString("messages.motd-status")), "实际 " + cfg.getString("messages.motd-status"));
        check("新命令 commands.金币榜 补齐（true）",
            cfg.getBoolean("commands.金币榜"), "未补齐");
        check("迁移后落盘（文件含 render: 段）",
            new String(Files.readAllBytes(folder.toPath().resolve("config.yml")), StandardCharsets.UTF_8).contains("render:"),
            "文件无 render 段");

        // 命令开关表：别名共享
        Map<String, Boolean> switches = manager.commandSwitches();
        check("命令开关表含金币榜", Boolean.TRUE.equals(switches.get("金币榜")), "缺失");
        check("命令开关表别名 在线 → 查在线 开关值",
            Boolean.TRUE.equals(switches.get("在线")), "别名未映射");
        managerReloadWithDisabledOnline(plugin, manager);

        System.out.println("\n== 4. 金币榜渲染（LeaderboardRenderer 泛化） ==");
        LeaderboardRenderer lb = LeaderboardRenderer.INSTANCE;
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        String[] amounts = {"152,340", "98,760", "45,201", "12,000", "9,876", "8,001", "5,500",
            "4,320", "3,210", "2,100", "1,980", "1,500", "1,200", "999", "880", "750", "600",
            "500", "400", "300", "250", "200", "150", "100", "50"};
        for (int i = 1; i <= 25; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "CoinsPlayer" + i, 0L, null, amounts[i - 1]));
        }
        int h1 = lb.measurePageHeight(15, true);
        int h2 = lb.measurePageHeight(10, false);
        check("金币榜首页高度与在线排行一致（15 行）", h1 == 1746, "实际 " + h1);
        check("金币榜续页高度（10 行）", h2 == 10 * 84 + 9 * 12 + 24 + 40, "实际 " + h2);

        BufferedImage bg = InfoCardAssets.INSTANCE.processedBuiltinBackground(
            InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD, 1200, h1);
        LeaderboardRenderer.LeaderboardData first = new LeaderboardRenderer.LeaderboardData(
            "KERONGPenguin", "16:40", entries.subList(0, 15), 25L, 15, bg, "金币排行榜", "金币");
        byte[] page1 = lb.renderPage(first, true);
        BufferedImage img1 = ImageIO.read(new ByteArrayInputStream(page1));
        check("金币榜首页出图 1200x" + h1, img1 != null && img1.getWidth() == 1200 && img1.getHeight() == h1,
            img1 == null ? "解码失败" : img1.getWidth() + "x" + img1.getHeight());
        // 标题像素区域有内容（非纯背景）：主标题基线约 y=148 的深色字
        boolean titleInk = false;
        for (int x = 40; x < 400; x += 4) {
            int rgb = img1.getRGB(x, 140);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            if (r > 200 && g > 200 && b > 200) { titleInk = true; break; }
        }
        check("首页标题区绘制了浅色文字（主标题）", titleInk, "标题区无文字像素");

        LeaderboardRenderer.LeaderboardData second = new LeaderboardRenderer.LeaderboardData(
            "KERONGPenguin", "16:40", entries.subList(15, 25), 25L, 15, null, "金币排行榜", "金币");
        byte[] page2 = lb.renderPage(second, false);
        BufferedImage img2 = ImageIO.read(new ByteArrayInputStream(page2));
        check("金币榜续页出图（无标题区）", img2 != null && img2.getWidth() == 1200 && img2.getHeight() == h2,
            img2 == null ? "解码失败" : img2.getWidth() + "x" + img2.getHeight());
        // 续页顶部 24px 内不应有标题文字（白像素）
        boolean contam = false;
        for (int x = 40; x < 500; x += 4) {
            int rgb = img2.getRGB(x, 10);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            if (r > 200 && g > 200 && b > 200) { contam = true; break; }
        }
        check("续页顶部无标题渗漏", !contam, "顶部出现文字像素");

        // 旧用法回归：不传 valueText（Java 传 null）→ 走 formatPlayTime
        LeaderboardRenderer.LeaderEntry legacy =
            new LeaderboardRenderer.LeaderEntry(1, "LegacyPlayer", 90000L, null, null);
        check("formatPlayTime 回归（90000s = 1天1小时）",
            "1天1小时".equals(lb.formatPlayTime(90000L)), "实际 " + lb.formatPlayTime(90000L));
        LeaderboardRenderer.LeaderboardData legacyPage = new LeaderboardRenderer.LeaderboardData(
            "KERONGPenguin", "16:40", List.of(legacy), 1L, 15, null, "在线排行榜", "总在线时长");
        byte[] legacyPng = lb.renderPage(legacyPage, true);
        check("旧用法（时长模式）渲染出图", legacyPng != null && legacyPng.length > 1000,
            "字节 " + (legacyPng == null ? 0 : legacyPng.length));

        // 保存可视样本
        File outDir = new File("build/smoke160");
        outDir.mkdirs();
        ImageIO.write(img1, "png", new File(outDir, "coins_first.png"));
        ImageIO.write(img2, "png", new File(outDir, "coins_second.png"));
        System.out.println("（样本已保存 " + outDir.getPath() + "）");

        System.out.println("\n== 5. 新类全量加载冒烟 ==");
        String[] classes = {
            "cn.huohuas001.huhobotPenguin.spigot.qqbind.MessageTemplates",
            "cn.huohuas001.huhobotPenguin.spigot.qqbind.EconomyName",
            "cn.huohuas001.huhobotPenguin.spigot.render.RenderSettings",
            "cn.huohuas001.bot.events.commands.CoinsLeaderboardCommands",
            "cn.huohuas001.bot.events.commands.CoinsLeaderboardCommands$Companion",
            "cn.huohuas001.bot.events.commands.CoinsLeaderboardCommands$RawEntry",
            "cn.huohuas001.bot.events.commands.PublicCommands",
            "cn.huohuas001.bot.events.commands.BindCommands",
            "cn.huohuas001.bot.events.commands.CheckInCommands",
            "cn.huohuas001.bot.events.commands.AdministrationCommands",
            "cn.huohuas001.bot.events.commands.MotdCommands",
            "cn.huohuas001.bot.events.GroupMessageHandler",
            "cn.huohuas001.bot.MenuManager",
            "cn.huohuas001.huhobotPenguin.spigot.scheduler.FoliaCompat",
            "cn.huohuas001.huhobotPenguin.spigot.scheduler.FoliaDelegate",
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler",
            "io.papermc.paper.threadedregions.scheduler.AsyncScheduler",
            "io.papermc.paper.threadedregions.scheduler.EntityScheduler",
            "io.papermc.paper.threadedregions.scheduler.ScheduledTask",
            "org.bukkit.scheduler.BukkitTask",
        };
        int loaded = 0;
        for (String cn : classes) {
            try {
                Class.forName(cn, false, Test160.class.getClassLoader());
                loaded++;
            } catch (Throwable t) {
                check("类加载 " + cn, false, t.toString());
            }
        }
        check("新类加载冒烟（" + classes.length + " 个）", loaded == classes.length, loaded + "/" + classes.length);

        System.out.println();
        System.out.println("结果：" + passed + " 通过，" + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    /** 关闭 查在线 开关后别名开关应同步为 false（共用开关验证）。 */
    static void managerReloadWithDisabledOnline(TestPlugin plugin, ConfigManager manager) {
        try {
            plugin.getConfig().set("commands.查在线", false);
            plugin.saveConfig();
            plugin.reloadConfig();
            manager.reload();
            Map<String, Boolean> switches = manager.commandSwitches();
            check("关闭 commands.查在线 后别名 /在线 一并失效",
                Boolean.FALSE.equals(switches.get("在线")), "别名仍为 " + switches.get("在线"));
        } catch (Throwable t) {
            check("关闭 commands.查在线 后别名 /在线 一并失效", false, t.toString());
        }
    }

    static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\n", "\\n") + "\"";
    }
}
