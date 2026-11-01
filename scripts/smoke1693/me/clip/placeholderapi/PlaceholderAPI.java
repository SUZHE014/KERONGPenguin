package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;

/**
 * 冒烟用假 PlaceholderAPI（仅独立 JVM 冒烟，不进任何产物）：
 * 模拟真实 PAPI 的 setPlaceholders 行为——按内容字典替换已知变量，
 * 未注册的变量原样返回（真实 PAPI 的语义），供 Test1693 验证
 * PlaceholderVariableService.resolve / resolveServerTps / resolveBatch
 * 的「成功采用 / 未解析回退」两条路径。
 * 1.6.9.3 扩展：新增玩家名（fake_nickname）与 TPS（fake_tps）假变量。
 */
public final class PlaceholderAPI {

    /** 已注册的假变量表（模拟装了某个经济/统计插件的 PAPI 扩展）。 */
    private static final java.util.Map<String, String> REGISTERED = java.util.Map.of(
            "%fake_coins%", "9999.5",
            "%fake_hours%", "77时20分",
            "%fake_title%", "&6[大佬]&f",
            "%fake_nickname%", "大老板",
            "%fake_tps%", "19.6");

    private PlaceholderAPI() {
    }

    public static String setPlaceholders(OfflinePlayer player, String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (java.util.Map.Entry<String, String> entry : REGISTERED.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        // 未注册的 %...% 原样保留（真实 PAPI 语义）
        return result;
    }
}
