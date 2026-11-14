import cn.kerong.bot.events.ButtonInteractionCooldown;
import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.qqbind.MessageTemplates;
import cn.kerong.penguin.spigot.qqbind.QqBindManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 冒烟：1.7.5.6 主线修复轮（覆盖轮）。
 *
 * 1. A1 模板升级=完整替换（释放记录机制，端到端真文件系统）
 * 2. A4 AI 提醒方式三模式（aiReminderMode 全链：新键优先 / 中文取值 /
 *    非法回退 / 空串回退旧键）
 * 3. A5 按钮互动冷却（真实时钟 5 秒门禁 / 穿透 / 清空 / 配置关闭）
 *
 * 类路径使用 NeoForge 分发 JAR（自带 org.bukkit 兼容层）。
 */
public class Smoke1756B {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  " + detail)); }
    }

    static String read(Path dir, String name) throws Exception {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** 与 MessageTemplates.normalize 同语义：剥全部空白与 BOM。 */
    static String normalize(String text) {
        return text.replaceAll("[\\s\\uFEFF]", "");
    }

    /** 与 MessageTemplates.fingerprint 同语义：normalize 后 SHA-256 小写 hex。 */
    static String fingerprint(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(normalize(text).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static final Pattern RECORD_ENTRY = Pattern.compile("\"([^\"]+)\":\"([0-9a-f]{64})\"");

    static Map<String, Object> loadRecord(Path dir) throws Exception {
        File f = dir.resolve(".release-record.json").toFile();
        Map<String, Object> result = new HashMap<>();
        if (!f.isFile()) return result;
        Matcher m = RECORD_ENTRY.matcher(Files.readString(f.toPath(), StandardCharsets.UTF_8));
        while (m.find()) result.put(m.group(1), m.group(2));
        return result;
    }

    static void saveRecord(Path dir, Map<String, Object> record) throws Exception {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : record.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        sb.append('}');
        Files.writeString(dir.resolve(".release-record.json"), sb.toString(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Files.createTempDirectory("krg-smoke-1756b");
        File dataFolder = dataDir.toFile();
        dataFolder.mkdirs();

        FakeKeronRuntime fake = new FakeKeronRuntime(dataFolder);
        BotShared.INSTANCE.setInstance(fake);
        Path markdown = dataDir.resolve("Markdown");

        // ================= 1. A1 模板升级 = 完整替换 =================
        // 首次初始化：释放全部默认模板 + 写释放记录
        MessageTemplates.INSTANCE.initialize();
        File[] released = markdown.toFile().listFiles((d, n) -> n.endsWith(".md"));
        int templateCount = MessageTemplates.INSTANCE.getTEMPLATE_IDS().size();
        check("S1 首次释放全量默认模板", released != null && released.length == templateCount,
                "files=" + (released == null ? 0 : released.length) + " ids=" + templateCount);
        Map<String, Object> record = loadRecord(markdown);
        check("S2 释放记录生成且条目数一致", record.size() == templateCount,
                "record=" + record.size() + " ids=" + templateCount);
        String menuBundled = readFromClasspath("Markdown/menu.md");
        check("S3 menu.md == 内置默认（含尾换行）",
                normalize(read(markdown, "menu.md")).equals(normalize(menuBundled)) &&
                        read(markdown, "menu.md").endsWith("\n"));

        // 路径③（表兜底，record 不匹配时）：menu.md 写回 1.7.5.2 版旧默认
        write(markdown, "menu.md",
                "### 指令菜单（{page}/{pages}）\n\n**当前插件版本：v{version}**");
        MessageTemplates.INSTANCE.initialize();
        String menuAfter = read(markdown, "menu.md");
        check("S4 旧默认 menu.md 整体替换为当前默认（非追加）",
                normalize(menuAfter).equals(normalize(menuBundled)) && !menuAfter.contains("当前插件版本"),
                menuAfter);

        // 路径②（释放记录命中，A1 核心价值——非表模板也能收敛）：
        // bind-success.md 不在升级表里，磁盘写哨兵内容 + 释放记录记哨兵指纹
        String sentinel = "OLDDEFAULT-SENTINEL-1756B";
        write(markdown, "bind-success.md", sentinel);
        record = loadRecord(markdown);
        record.put("bind-success.md", fingerprint(sentinel));
        saveRecord(markdown, record);
        MessageTemplates.INSTANCE.initialize();
        String bindAfter = read(markdown, "bind-success.md");
        String bindBundled = readFromClasspath("Markdown/bind-success.md");
        check("S5 非表模板凭释放记录整体替换（旧默认自动收敛）",
                normalize(bindAfter).equals(normalize(bindBundled)) && !bindAfter.contains("SENTINEL"),
                bindAfter);

        // 用户自改（指纹不匹配且无表条目）→ 永不动
        write(markdown, "blacklist-list.md", "MYCUSTOM-BLACKLIST-{count}");
        MessageTemplates.INSTANCE.initialize();
        check("S6 用户自改模板不动", read(markdown, "blacklist-list.md").equals("MYCUSTOM-BLACKLIST-{count}"));

        // 幂等：再跑一次，全部文件 mtime 稳定
        Map<String, Long> mtimes = new HashMap<>();
        for (File f : markdown.toFile().listFiles((d, n) -> n.endsWith(".md"))) {
            mtimes.put(f.getName(), f.lastModified());
        }
        Thread.sleep(20);
        MessageTemplates.INSTANCE.initialize();
        boolean stable = true;
        for (Map.Entry<String, Long> e : mtimes.entrySet()) {
            File f = markdown.resolve(e.getKey()).toFile();
            if (f.lastModified() != e.getValue()) { stable = false; break; }
        }
        check("S7 幂等（第二次运行零写入）", stable);

        // BOM/换行容差：等于当前默认（仅格式差异）→ 不替换，内容保持
        String bom = "\uFEFF" + menuBundled.replace("\n", "\r\n");
        write(markdown, "menu.md", bom);
        MessageTemplates.INSTANCE.initialize();
        check("S8 BOM/换行差异视为当前默认不替换", read(markdown, "menu.md").equals(bom));

        // ================= 2. A5 按钮互动冷却（真实时钟） =================
        // 默认 5 秒（fake config 为空 → getInt 取默认）
        ButtonInteractionCooldown bic = ButtonInteractionCooldown.INSTANCE;
        bic.clearAll();
        boolean first = bic.tryAcquire("g1", "u1");
        boolean second = bic.tryAcquire("g1", "u1");
        check("S9 冷却内第二次点击被拒", first && !second);
        boolean otherUser = bic.tryAcquire("g1", "u2");
        check("S10 其他用户不受影响（键=群|用户）", otherUser);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 5100) { Thread.sleep(200); }
        boolean afterInterval = bic.tryAcquire("g1", "u1");
        check("S11 间隔期满后放行（默认 5 秒）", afterInterval, "wait=" + (System.currentTimeMillis() - t0) + "ms");
        check("S12 空用户身份穿透（无法归属不冷却）", bic.tryAcquire("g", ""));
        bic.clearAll();
        check("S13 clearAll 后立即可再点", bic.tryAcquire("g1", "u1"));
        fake.getConfig().set("bot.button-cooldown-seconds", 0);
        bic.clearAll();
        boolean offA = bic.tryAcquire("g2", "u1");
        boolean offB = bic.tryAcquire("g2", "u1");
        check("S14 配置 0 = 关闭冷却（连点都放行）", offA && offB);
        fake.getConfig().set("bot.button-cooldown-seconds", 5);

        // ================= 3. A4 AI 提醒方式三模式 =================
        QqBindManager manager = QqBindManager.Companion.getInstance();
        fake.getConfig().set("ai.reminder-mode", null);
        fake.getConfig().set("ai.reply-quote", null);
        check("S15 双键缺省 = quote（旧行为）", "quote".equals(manager.aiReminderMode()),
                manager.aiReminderMode());
        fake.getConfig().set("ai.reply-quote", false);
        fake.getConfig().set("ai.reminder-mode", null);
        check("S16 旧键 false → none（回退链）", "none".equals(manager.aiReminderMode()),
                manager.aiReminderMode());
        fake.getConfig().set("ai.reminder-mode", "at");
        check("S17 新键 at 优先于旧键 false", "at".equals(manager.aiReminderMode()),
                manager.aiReminderMode());
        fake.getConfig().set("ai.reminder-mode", "艾特");
        check("S18 中文 艾特 → at", "at".equals(manager.aiReminderMode()), manager.aiReminderMode());
        fake.getConfig().set("ai.reminder-mode", "引用");
        check("S19 中文 引用 → quote", "quote".equals(manager.aiReminderMode()), manager.aiReminderMode());
        fake.getConfig().set("ai.reminder-mode", "无");
        check("S20 中文 无 → none", "none".equals(manager.aiReminderMode()), manager.aiReminderMode());
        fake.getConfig().set("ai.reminder-mode", "garbage");
        check("S21 非法取值 → quote 兜底", "quote".equals(manager.aiReminderMode()), manager.aiReminderMode());
        fake.getConfig().set("ai.reminder-mode", "");
        fake.getConfig().set("ai.reply-quote", false);
        check("S22 新键空串 + 旧键 false → none", "none".equals(manager.aiReminderMode()),
                manager.aiReminderMode());
        fake.getConfig().set("ai.reply-quote", true);
        check("S23 新键空串 + 旧键 true → quote", "quote".equals(manager.aiReminderMode()),
                manager.aiReminderMode());

        System.out.println();
        System.out.println("===== 冒烟结果:" + passed + "/" + (passed + failed) + " =====");
        if (failed > 0) { System.out.println(failed + " 项失败"); System.exit(1); }
        System.out.println("全部通过");
    }

    static String readFromClasspath(String resource) throws Exception {
        try (java.io.InputStream in = Smoke1756B.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
