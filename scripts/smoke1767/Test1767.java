import cn.kerong.penguin.spigot.qqbind.CheckinRewardSpec;

/**
 * 1.7.6.7 冒烟（独立 JVM，真实类，Spigot 分发 JAR 做类路径）：
 *
 * CR 组 CheckinRewardSpec（签到金币奖励固定/随机双写法纯函数）——
 *   parseQuietly：null / Number / 纯数字字符串 → Fixed；「最小~最大」→ Range
 *     （两端排序、负下限夹 0、trim 容忍）；写错（空段 / 非数字 / 多段）→ null；
 *   maxAmount：启用判定口径（Range 上界 / Fixed 值；0 = 关闭）；
 *   roll：固定恒定；整数区间抽整数且在区间内（含端点、多样性）；
 *     小数区间两位小数内；退化区间（min==max）恒定；
 *   displayText：「100」/「12.5」/「0~100」；
 *   parse：写错值回退默认 Fixed(100)（不抛异常不返回 null）。
 * QN 组 群发消息——
 *   QClient.broadcastAdminNotice 未连接（starter == null）安全返回 (0,0)
 *     不触网不抛异常（真实方法调用：方法在位、空态语义正确）。
 * CB 组 CheckInCommands 随机标注常量——
 *   类字节含「（随机 」标注文案（随机模式下签到回复带区间标注）。
 *
 * 编译运行：javac/java -cp Spigot 分发 JAR（类与依赖全在包内）。
 */
public class Test1767 {
    static int pass = 0, fail = 0;
    static final java.util.List<String> fails = new java.util.ArrayList<>();

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; fails.add(name); System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== CR 组：CheckinRewardSpec 解析 ==");

        // ---- CR1 parseQuietly：固定形态 ----
        check("CR1a null → 默认 Fixed(100)", eq(Fixed(100), CheckinRewardSpec.parseQuietly(null)));
        check("CR1b 数字 100.0 → Fixed", eq(Fixed(100.0), CheckinRewardSpec.parseQuietly(100.0)));
        check("CR1c 整数 50 → Fixed", eq(Fixed(50.0), CheckinRewardSpec.parseQuietly(50)));
        check("CR1d 字符串 \"100\" → Fixed", eq(Fixed(100.0), CheckinRewardSpec.parseQuietly("100")));
        check("CR1e 小数字符串 → Fixed", eq(Fixed(12.5), CheckinRewardSpec.parseQuietly("12.5")));
        check("CR1f 空串 → 默认", eq(Fixed(100), CheckinRewardSpec.parseQuietly("")));
        check("CR1g 空白串 → 默认", eq(Fixed(100), CheckinRewardSpec.parseQuietly("   ")));

        // ---- CR2 parseQuietly：随机区间形态 ----
        check("CR2a \"0~100\" → Range(0,100)", eq(Range(0, 100), CheckinRewardSpec.parseQuietly("0~100")));
        check("CR2b \"50~150\" → Range(50,150)", eq(Range(50, 150), CheckinRewardSpec.parseQuietly("50~150")));
        check("CR2c 倒序 \"150~50\" 自动排序", eq(Range(50, 150), CheckinRewardSpec.parseQuietly("150~50")));
        check("CR2d 负下限 \"-5~100\" 夹 0", eq(Range(0, 100), CheckinRewardSpec.parseQuietly("-5~100")));
        check("CR2e trim 容忍 \"  0~100  \"", eq(Range(0, 100), CheckinRewardSpec.parseQuietly("  0~100  ")));
        check("CR2f 内空格 \"0 ~ 100\"", eq(Range(0, 100), CheckinRewardSpec.parseQuietly("0 ~ 100")));
        check("CR2g 小数区间 \"10.5~20.5\"", eq(Range(10.5, 20.5), CheckinRewardSpec.parseQuietly("10.5~20.5")));
        check("CR2h \"0~0\" → Range(0,0)（关闭语义）", eq(Range(0, 0), CheckinRewardSpec.parseQuietly("0~0")));

        // ---- CR3 parseQuietly：写错形态 → null ----
        check("CR3a \"50~\" → null", CheckinRewardSpec.parseQuietly("50~") == null);
        check("CR3b \"~100\" → null", CheckinRewardSpec.parseQuietly("~100") == null);
        check("CR3c \"a~b\" → null", CheckinRewardSpec.parseQuietly("a~b") == null);
        check("CR3d 多段 \"1~2~3\" → null", CheckinRewardSpec.parseQuietly("1~2~3") == null);
        check("CR3e \"abc\" → null", CheckinRewardSpec.parseQuietly("abc") == null);
        check("CR3f 布尔值形态 → null", CheckinRewardSpec.parseQuietly(Boolean.TRUE) == null);

        // ---- CR4 maxAmount 启用判定 ----
        check("CR4a Range(0,100) 上界 100", CheckinRewardSpec.parseQuietly("0~100").getMaxAmount() == 100.0);
        check("CR4b Range(0,0) 上界 0（关闭）", CheckinRewardSpec.parseQuietly("0~0").getMaxAmount() == 0.0);
        check("CR4c Fixed(100) 上界 100", CheckinRewardSpec.parseQuietly("100").getMaxAmount() == 100.0);
        check("CR4d Fixed(0) 上界 0（关闭）", CheckinRewardSpec.parseQuietly(0.0).getMaxAmount() == 0.0);

        // ---- CR5 roll：固定模式 ----
        CheckinRewardSpec fixed = CheckinRewardSpec.parseQuietly("88.8");
        boolean fixedStable = true;
        for (int i = 0; i < 10; i++) if (fixed.roll() != 88.8) fixedStable = false;
        check("CR5a Fixed roll 恒定 88.8", fixedStable);

        // ---- CR6 roll：整数区间随机 ----
        CheckinRewardSpec range = CheckinRewardSpec.parseQuietly("0~100");
        boolean inRange = true, allInteger = true, diverse = false;
        double first = range.roll();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            double v = range.roll();
            if (v < 0 || v > 100) inRange = false;
            if (v != Math.floor(v)) allInteger = false;
            seen.add((long) v);
            if (v != first) diverse = true;
        }
        check("CR6a 500 次采样全在 [0,100]", inRange);
        check("CR6b 两端整数 → 抽整数", allInteger);
        check("CR6c 采样多样性（真随机非恒定）", diverse && seen.size() > 10);
        check("CR6d 区间含端点可命中", seen.contains(0L) && seen.contains(100L));

        // ---- CR7 roll：小数区间与退化区间 ----
        CheckinRewardSpec frac = CheckinRewardSpec.parseQuietly("0.5~1.5");
        boolean fracOk = true;
        for (int i = 0; i < 300; i++) {
            double v = frac.roll();
            if (v < 0.5 || v > 1.5) fracOk = false;
            if (Math.abs(v * 100 - Math.round(v * 100)) > 0.001) fracOk = false;
        }
        check("CR7a 小数区间在界内且两位小数", fracOk);
        CheckinRewardSpec degenerate = CheckinRewardSpec.parseQuietly("7~7");
        boolean degenerateStable = true;
        for (int i = 0; i < 20; i++) if (degenerate.roll() != 7.0) degenerateStable = false;
        check("CR7b 退化区间 7~7 恒 7", degenerateStable);

        // ---- CR8 displayText ----
        check("CR8a Fixed(100) 文本 \"100\"", "100".equals(CheckinRewardSpec.parseQuietly("100.0").displayText()));
        check("CR8b Fixed(12.5) 文本 \"12.5\"", "12.5".equals(CheckinRewardSpec.parseQuietly("12.5").displayText()));
        check("CR8c Range 文本 \"0~100\"", "0~100".equals(CheckinRewardSpec.parseQuietly("0~100").displayText()));
        check("CR8d Range 小数文本", "10.5~20.5".equals(CheckinRewardSpec.parseQuietly("10.5~20.5").displayText()));

        // ---- CR9 parse（带日志回退，不抛异常）----
        try {
            CheckinRewardSpec fallback = CheckinRewardSpec.parse("垃圾配置~");
            check("CR9a 写错值 parse 回退默认 Fixed(100)", eq(Fixed(100), fallback));
        } catch (Throwable t) {
            check("CR9a 写错值 parse 回退默认 Fixed(100)", false);
        }
        check("CR9b 正常值 parse 透传", eq(Range(0, 100), CheckinRewardSpec.parse("0~100")));

        System.out.println("== QN 组：群发消息（未连接安全语义）==");

        // QClient 未连接（starter == null）时群发安全返回 (0,0)，不触网不抛异常
        try {
            kotlin.Pair<Integer, Integer> r = cn.kerong.bot.QClient.INSTANCE.broadcastAdminNotice("测试内容");
            check("QN1 未连接返回 (0,0)", r.getFirst() == 0 && r.getSecond() == 0);
        } catch (Throwable t) {
            System.out.println("    异常: " + t);
            check("QN1 未连接返回 (0,0)", false);
        }
        try {
            kotlin.Pair<Integer, Integer> r = cn.kerong.bot.QClient.INSTANCE.broadcastAdminNotice("");
            check("QN2 空内容返回 (0,0)", r.getFirst() == 0 && r.getSecond() == 0);
        } catch (Throwable t) {
            check("QN2 空内容返回 (0,0)", false);
        }

        System.out.println("== CB 组：CheckInCommands 随机标注常量 ==");

        // 分发 JAR 内 CheckInCommands 类字节含随机标注文案（随机模式签到回复带「（随机 0~100）」）
        String jarPath = System.getProperty("java.class.path").split(java.io.File.pathSeparator)[0];
        byte[] cls = readJarEntry(jarPath, "cn/kerong/bot/events/commands/CheckInCommands.class");
        check("CB1 CheckInCommands.class 在包内", cls != null);
        if (cls != null) {
            String utf8 = new String(cls, java.nio.charset.StandardCharsets.ISO_8859_1);
            check("CB2 类字节含「（随机 」标注", utf8.contains(new String("（随机 ".getBytes("UTF-8"), java.nio.charset.StandardCharsets.ISO_8859_1)));
        }

        System.out.println();
        System.out.println("通过 " + pass + "，失败 " + fail);
        if (!fails.isEmpty()) {
            System.out.println("失败项:");
            for (String f : fails) System.out.println("  - " + f);
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    static CheckinRewardSpec.Fixed Fixed(double v) {
        return new CheckinRewardSpec.Fixed(v);
    }

    static CheckinRewardSpec.Range Range(double min, double max) {
        return new CheckinRewardSpec.Range(min, max);
    }

    static boolean eq(CheckinRewardSpec a, CheckinRewardSpec b) {
        return a != null && a.equals(b);
    }

    static byte[] readJarEntry(String jarPath, String entryName) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
            java.util.jar.JarEntry e = jar.getJarEntry(entryName);
            if (e == null) return null;
            try (java.io.InputStream in = jar.getInputStream(e);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception ex) {
            return null;
        }
    }
}
