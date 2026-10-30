import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * smoke16710 —— 1.6.7.10 快捷面板按钮上限裁剪行为冒烟（独立 JVM）。
 *
 * 验证 30013 真根因修复：QQ 官方限制单个面板最多 20 个元素，
 * trimToPlatformLimit 必须把超限按钮列表裁到 20，且按 PANEL_TRIM_ORDER
 * 优先裁低频管理按钮（全功能开启 21 项 → 裁掉「查询open ID」→ 20 项）。
 *
 * 编译：javac -cp <dist.jar>:<deps.jar> Test16710.java
 * 运行：java  -cp .:<dist.jar>:<deps.jar> Test16710
 */
public class Test16710 {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) { check(name, cond, ""); }

    static void check(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : " — " + detail)); }
        else { fail++; System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : " — " + detail)); }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Class<?> menu = Class.forName("cn.kerong.bot.MenuManager");
        Object instance = menu.getDeclaredField("INSTANCE").get(null);

        // ---- 常量与配置字段 ----
        // PANEL_MAX_ITEMS 是 const val（编译期内联），无字段——通过行为验证；
        // PANEL_TRIM_ORDER / LIMIT_RETRY_BACKOFF_MILLIS 是实例字段
        Field trimOrderField = menu.getDeclaredField("PANEL_TRIM_ORDER");
        trimOrderField.setAccessible(true);
        List<String> trimOrder = (List<String>) trimOrderField.get(instance);
        check("TRIM1 裁剪顺序非空且首位为查询open ID",
                trimOrder != null && !trimOrder.isEmpty() && "查询open ID".equals(trimOrder.get(0)),
                String.valueOf(trimOrder));

        Field backoffField = menu.getDeclaredField("LIMIT_RETRY_BACKOFF_MILLIS");
        backoffField.setAccessible(true);
        long[] backoff = (long[]) backoffField.get(instance);
        check("TRIM2 30013 退避 2.5s/5s 递增",
                backoff != null && backoff.length >= 2 && backoff[0] == 2500L && backoff[1] == 5000L,
                backoff == null ? "null" : backoff[0] + "/" + backoff[1]);

        // ---- PanelItem 构造（私有数据类） ----
        Class<?> itemClass = Class.forName("cn.kerong.bot.MenuManager$PanelItem");
        Constructor<?> ctor = itemClass.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);

        Method getName = itemClass.getDeclaredMethod("component1");
        getName.setAccessible(true);

        Method trim = menu.getDeclaredMethod("trimToPlatformLimit", List.class);
        trim.setAccessible(true);

        // ---- 场景 1：全功能 21 项（真实按钮清单）→ 裁到 20，裁掉「查询open ID」 ----
        String[] all21 = {
                "帮助", "查询open ID", "查信息", "绑定", "重新绑定", "查在线", "查性能",
                "在线排行榜", "财富榜", "在线服务器", "发信息", "执行命令", "执行",
                "管理员执行", "全量", "motd", "AI对话上下文", "清除当前上下文",
                "黑名单", "解除黑名单", "签到",
        };
        List<Object> items21 = new ArrayList<>();
        for (String n : all21) items21.add(ctor.newInstance(n, "desc"));
        List<?> trimmed = (List<?>) trim.invoke(instance, new ArrayList<>(items21));
        boolean hasOpenId = false;
        for (Object o : trimmed) {
            if (((String) getName.invoke(o)).equals("查询open ID")) hasOpenId = true;
        }
        check("TRIM3 21 项裁到 20", trimmed.size() == 20, "实际 " + trimmed.size());
        check("TRIM4 裁掉的是「查询open ID」", !hasOpenId);
        boolean orderKept = true;
        int j = 0;
        for (String n : all21) {
            if (n.equals("查询open ID")) continue;
            if (!(((String) getName.invoke(trimmed.get(j))).equals(n))) { orderKept = false; break; }
            j++;
        }
        check("TRIM5 其余 20 项保持原顺序", orderKept && j == 20);

        // ---- 场景 2：15 项（部分功能关闭）→ 不裁剪 ----
        List<Object> items15 = new ArrayList<>();
        for (int i = 0; i < 15; i++) items15.add(ctor.newInstance("按钮" + i, "d"));
        List<?> trimmed15 = (List<?>) trim.invoke(instance, new ArrayList<>(items15));
        check("TRIM6 15 项不裁剪", trimmed15.size() == 15, "实际 " + trimmed15.size());

        // ---- 场景 3：25 项且裁剪表只覆盖部分 → 兜底从末尾截断 ----
        List<Object> items25 = new ArrayList<>();
        for (String n : all21) items25.add(ctor.newInstance(n, "d"));
        for (int i = 0; i < 4; i++) items25.add(ctor.newInstance("自定义" + i, "d"));
        List<?> trimmed25 = (List<?>) trim.invoke(instance, new ArrayList<>(items25));
        check("TRIM7 25 项裁到 20（兜底截断）", trimmed25.size() == 20, "实际 " + trimmed25.size());

        // ---- 场景 4：syncGroupPanels 空群返回失败结果（结果管道） ----
        // starter 非空参数检查在方法入口即触发，空群早退路径不触碰 starter，
        // 传入一个仅构造不启动的 Starter 实例（构造函数只存字段）
        Class<?> starterClass = Class.forName("io.github.kloping.qqbot.Starter");
        Object starter = starterClass.getDeclaredConstructor(String.class, String.class)
                .newInstance("100000", "test-secret");
        Method sync = menu.getDeclaredMethod("syncGroupPanelsWithResult", starterClass, List.class);
        Object result = sync.invoke(instance, starter, new ArrayList<String>());
        // 兼容桥：void 签名保持（互转版旧编译产物按此签名调用，替换不报 NoSuchMethodError）
        Method syncVoid = menu.getDeclaredMethod("syncGroupPanels", starterClass, List.class);
        check("RES0 void 兼容桥存在", syncVoid.getReturnType() == void.class);
        syncVoid.invoke(instance, starter, new ArrayList<String>());
        Class<?> resultClass = Class.forName("cn.kerong.bot.MenuManager$PanelSyncResult");
        Method ok = resultClass.getDeclaredMethod("getOk");
        Method getError = resultClass.getDeclaredMethod("getError");
        check("RES1 空群 ok=false", !((Boolean) ok.invoke(result)));
        String err = (String) getError.invoke(result);
        check("RES2 空群返回原因", err != null && err.contains("未配置 QQ 群"), String.valueOf(err));

        // ---- 场景 5：PanelSyncResult 字段齐全 ----
        for (String f : new String[]{"panelId", "buttonCount", "groupCount", "error"})
            check("RES3 字段[" + f + "]", resultClass.getDeclaredField(f) != null);

        System.out.println();
        System.out.println("== smoke16710: " + pass + "/" + (pass + fail) + " PASS ==");
        if (fail > 0) System.exit(1);
    }
}
