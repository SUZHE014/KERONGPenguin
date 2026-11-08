#!/usr/bin/env python3
"""1.7.4 第四轮覆盖版冒烟测试（独立 JVM，反射调用真实类）。

覆盖本轮改动的运行时面：
1. RenderSettings：副标题灰度默认 106（无插件实例回退默认）
2. ConfigManager：config-version 37
3. MessageTemplates：player-lookup 模板登记 + 资源占位符 + 无实例渲染静默
4. PublicCommands：lookupByPlayerName 方法签名（玩家名反查入口）
5. PlayerSelection：多玩家选择按钮键盘（label=玩家名，@路径共用）
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
JARS = [
    REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.4.jar",
    Path("/home/z/my-project/tooling/gradle-home/caches/modules-2/files-2.1/"
         "io.papermc.paper/paper-api/1.20.4-R0.1-SNAPSHOT/"
         "8b9cca4e56ef91855140e918ec50cb4ed5189d81/paper-api-1.20.4-R0.1-SNAPSHOT.jar"),
]
CP = ":".join(str(j) for j in JARS)

JAVA_TEST = r'''
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Smoke174o4 {
    static int passed = 0, failed = 0;
    static void check(String name, boolean ok) { check(name, ok, ""); }
    static void check(String name, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "OK " : "FAIL ") + name + (ok || detail.isEmpty() ? "" : " — " + detail));
    }

    public static void main(String[] args) throws Exception {
        // 1. RenderSettings 副标题默认 106
        Class<?> rs = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings");
        Field subGray = rs.getDeclaredField("DEFAULT_SUBTITLE_CARD_GRAY");
        subGray.setAccessible(true);
        check("副标题灰度默认常量 106", subGray.getInt(null) == 106, String.valueOf(subGray.getInt(null)));
        Field itemGray = rs.getDeclaredField("DEFAULT_ITEM_CARD_GRAY");
        itemGray.setAccessible(true);
        check("项目卡片灰度默认 106（对齐）", itemGray.getInt(null) == 106);
        Object rsInstance = rs.getField("INSTANCE").get(null);
        Method subtitleCardGray = rs.getMethod("subtitleCardGray");
        int live = (Integer) subtitleCardGray.invoke(rsInstance);
        check("subtitleCardGray() 无插件实例回退 106", live == 106, String.valueOf(live));

        // 2. ConfigManager config-version 37
        Class<?> cm = Class.forName("cn.kerong.penguin.spigot.manager.ConfigManager");
        Field ver = cm.getDeclaredField("CURRENT_CONFIG_VERSION");
        ver.setAccessible(true);
        check("config-version 37", ver.getInt(null) == 37, String.valueOf(ver.getInt(null)));

        // 3. MessageTemplates 模板登记
        Class<?> mt = Class.forName("cn.kerong.penguin.spigot.qqbind.MessageTemplates");
        Object mtInstance = mt.getField("INSTANCE").get(null);
        Field idsField = mt.getDeclaredField("TEMPLATE_IDS");
        idsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Collection<String> ids = (java.util.Collection<String>) idsField.get(mtInstance);
        check("TEMPLATE_IDS 含 player-lookup", ids.contains("player-lookup"));
        check("TEMPLATE_IDS 含 openid-lookup（保留）", ids.contains("openid-lookup"));
        check("TEMPLATE_IDS 无 openid-bindcode（绑定码已删）", !ids.contains("openid-bindcode"));
        InputStream tpl = mt.getClassLoader().getResourceAsStream("Markdown/player-lookup.md");
        String content = tpl == null ? "" : new String(tpl.readAllBytes(), StandardCharsets.UTF_8);
        check("player-lookup.md 资源可读", !content.isEmpty());
        check("player-lookup.md 占位符齐全",
                content.contains("{player}") && content.contains("{openidPart}")
                        && content.contains("{nickname}") && content.contains("{players}"));
        // 无插件实例渲染回退 JAR 内置模板（不崩、占位符按传入变量替换）
        Method render = mt.getMethod("render", String.class, java.util.Map.class);
        String out = (String) render.invoke(mtInstance, "player-lookup",
                java.util.Map.of("player", "Steve", "players", "Steve、Alex", "openidPart", "", "nickname", "测试昵称"));
        check("render 回退内置模板并完成变量替换",
                out != null && out.contains("Steve、Alex") && out.contains("测试昵称") && !out.contains("{players}"), "'" + out + "'");

        // 4. PublicCommands.lookupByPlayerName 签名（玩家名反查入口）
        Class<?> pc = Class.forName("cn.kerong.bot.events.commands.PublicCommands");
        Method lookup = null;
        for (Method m : pc.getDeclaredMethods()) {
            if (m.getName().equals("lookupByPlayerName")) { lookup = m; break; }
        }
        check("lookupByPlayerName 方法存在", lookup != null);
        if (lookup != null) {
            Class<?>[] pts = lookup.getParameterTypes();
            boolean sigOk = pts.length == 3
                    && pts[0].getName().equals("cn.kerong.bot.KERONG")
                    && pts[1].getName().equals("io.github.kloping.qqbot.api.v2.GroupMessageEvent")
                    && pts[2] == String.class
                    && lookup.getReturnType() == boolean.class;
            check("lookupByPlayerName 签名 (KERONG, GroupMessageEvent, String) → boolean", sigOk,
                    java.util.Arrays.toString(pts) + " -> " + lookup.getReturnType());
        }
        Method queryInfo = null;
        for (Method m : pc.getDeclaredMethods()) {
            if (m.getName().equals("queryInfo")) { queryInfo = m; break; }
        }
        check("queryInfo 方法存在（查信息入口）", queryInfo != null);

        // 5. PlayerSelection 选择按钮键盘（多绑定共用，label=玩家名）
        Class<?> kbClass = Class.forName("cn.kerong.bot.tools.Keyboards");
        Object kbInstance = kbClass.getField("INSTANCE").get(null);
        Class<?> btnClass = Class.forName("cn.kerong.bot.tools.Keyboards$Button");
        Object b1 = btnClass.getConstructor(String.class, String.class, String.class, int.class)
                .newInstance("Steve", "已选择 Steve", "krg:psel:Steve", 1);
        Object b2 = btnClass.getConstructor(String.class, String.class, String.class, int.class)
                .newInstance("Alex", "已选择 Alex", "krg:psel:Alex", 1);
        Method build = kbClass.getDeclaredMethod("build", List.class);
        build.setAccessible(true);
        Object keyboard = build.invoke(kbInstance, List.of(b1, b2));
        Object contentJson = keyboard.getClass().getMethod("getContent").invoke(keyboard);
        Object rows = ((com.alibaba.fastjson.JSONObject) contentJson).getJSONArray("rows");
        check("玩家选择键盘 rows 非空（按钮可渲染）", rows != null && ((com.alibaba.fastjson.JSONArray) rows).size() >= 1);

        // 6. Keyboards 动作前缀（psel 前缀与反查无冲突：只验证常量仍在）
        Class<?> ps = Class.forName("cn.kerong.bot.events.PlayerSelection");
        Object psInstance = ps.getField("INSTANCE").get(null);
        Field prefix = ps.getDeclaredField("ACTION_PREFIX");
        prefix.setAccessible(true);
        check("PlayerSelection 按钮前缀保留 krg:psel:", "krg:psel:".equals(prefix.get(psInstance)));

        System.out.println("SMOKE_RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
'''
out_dir = Path("/tmp/smoke174o4")
out_dir.mkdir(exist_ok=True)
(out_dir / "Smoke174o4.java").write_text(JAVA_TEST)
javac = "/home/z/my-project/tooling/jdk21/bin/javac"
java = "/home/z/my-project/tooling/jdk21/bin/java"
r = subprocess.run([javac, "-cp", CP, "-d", str(out_dir), str(out_dir / "Smoke174o4.java")],
                   capture_output=True, text=True)
if r.returncode != 0:
    print(r.stderr[:3000]); sys.exit(1)
r = subprocess.run([java, "-cp", f"{CP}:{out_dir}", "Smoke174o4"], capture_output=True, text=True)
print(r.stdout)
if r.returncode != 0:
    print(r.stderr[:2000]); sys.exit(1)
