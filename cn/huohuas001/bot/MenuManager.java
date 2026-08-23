package cn.huohuas001.bot;
import cn.huohuas001.bot.provider.BotShared;
import com.alibaba.fastjson.*;
import io.github.kloping.qqbot.Start0;
import io.github.kloping.qqbot.Starter;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MenuManager {
    public static final MenuManager INSTANCE = new MenuManager();
    private static final String API_BASE = "https://api.sgroup.qq.com";
    private static final List<PanelItem> PANEL_ITEMS = Collections.unmodifiableList(Arrays.asList(
            new PanelItem("帮助","查看所有命令","帮助"),
            new PanelItem("查信息","查询 OpenId","查信息 "),
            new PanelItem("绑定","绑定 QQ","绑定 "),
            new PanelItem("重新绑定","解除 QQ 绑定","重新绑定"),
            new PanelItem("查在线","查询在线玩家","查在线"),
            new PanelItem("在线服务器","查看服务器","在线服务器"),
            new PanelItem("发信息","发送消息","发信息 "),
            new PanelItem("执行命令","执行服务器命令","执行命令 "),
            new PanelItem("执行","执行自定义命令","执行 "),
            new PanelItem("管理员执行","管理员执行","管理员执行 "),
            new PanelItem("全量","切换全量转发","全量"),
            new PanelItem("motd","查询服务器","motd "),
            new PanelItem("AI对话上下文","AI对话上下文","AI对话上下文 "),
            new PanelItem("清除当前上下文","AI清除","清除当前上下文"),
            new PanelItem("黑名单","黑名单","黑名单 "),
            new PanelItem("解除黑名单","解除黑名单","解除黑名单"),
            new PanelItem("签到","每日签到领金币","签到")
    ));
    private static final Set<String> QQ_BIND_PANEL_NAMES = new HashSet<>(Arrays.asList("绑定","重新绑定","黑名单","解除黑名单"));
    private static final Set<String> CHECKIN_PANEL_NAMES = new HashSet<>(Arrays.asList("签到"));
    private MenuManager() {}
    public void syncGroupPanels(Starter starter, List<String> groupOpenIds) {
        if (groupOpenIds == null || groupOpenIds.isEmpty()) return;
        try {
            Start0 start0 = (Start0) starter.APPLICATION.INSTANCE.getContextManager().getContextEntity(Start0.class);
            String token = start0.getAccessToken(); if (token == null) return;
            String authHeader = "QQBot " + token;
            List<JSONObject> panels = listPanels(authHeader, "group"); for (int i = 0; i < panels.size(); i++) { JSONObject panel = panels.get(i); String id = panel.getString("panel_id"); if (id != null) deletePanel(authHeader, id); }
            Map<String,Boolean> cmdList = Collections.emptyMap(); boolean qqBindEnabled = false; boolean checkinEnabled = false;
            try { HuHoBot huHoBot = BotShared.INSTANCE.getPlugin(); if (huHoBot != null) { cmdList = huHoBot.getCommandList(); cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager mgr = cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager.getInstance(); qqBindEnabled = mgr.isEnabled(); checkinEnabled = mgr.isCheckinEnabled(); } } catch (Throwable ignored) {}
            List<PanelItem> visible = new ArrayList<>();
            for (int i = 0; i < PANEL_ITEMS.size(); i++) { PanelItem item = PANEL_ITEMS.get(i); if (!qqBindEnabled && QQ_BIND_PANEL_NAMES.contains(item.getName())) continue; if (!checkinEnabled && CHECKIN_PANEL_NAMES.contains(item.getName())) continue; Boolean enabled = cmdList.get(item.getName()); if (enabled == null || enabled) visible.add(item); }
            JSONObject body = new JSONObject(); body.put("scope","group"); body.put("target_type","specific");
            JSONArray gids = new JSONArray(); gids.addAll(groupOpenIds); body.put("group_openids", gids);
            JSONObject panel = new JSONObject(); panel.put("remark","KERONG Penguin");
            JSONArray items = new JSONArray();
            for (int i = 0; i < visible.size(); i++) { PanelItem item = visible.get(i); JSONObject io = new JSONObject(); io.put("type","command"); io.put("name",item.getName()); io.put("desc",item.getDesc()); items.add(io); }
            panel.put("items", items); body.put("panel", panel);
            createPanel(authHeader, body);
        } catch (Exception e) { HuHoBot plugin = BotShared.INSTANCE.getPlugin(); if (plugin != null) plugin.log_error("面板同步失败: " + e.getMessage()); }
    }
    public String getCommandTrigger(String name) { if (name == null) return null; for (PanelItem it : PANEL_ITEMS) if (name.equals(it.getName())) return it.getCommand(); return null; }
    @SuppressWarnings("unchecked")
    private List<JSONObject> listPanels(String authHeader, String scope) { HttpURLConnection conn = null; try { URL url = new URL(API_BASE+"/v2/panels?scope="+scope+"&limit=50"); conn = (HttpURLConnection) url.openConnection(); conn.setRequestMethod("GET"); conn.setRequestProperty("Authorization", authHeader); int code = conn.getResponseCode(); InputStream stream = (code>=200&&code<300)?conn.getInputStream():conn.getErrorStream(); String text = readAll(stream); JSONObject body = JSON.parseObject(text != null ? text : "{}"); JSONArray records = body != null ? body.getJSONArray("records") : null; if (records == null) return Collections.emptyList(); List<JSONObject> r = new ArrayList<>(); for (int i = 0; i < records.size(); i++) { Object o = records.get(i); if (o instanceof JSONObject) { r.add((JSONObject) o); } } return r; } catch (Exception e) { return Collections.emptyList(); } finally { if (conn != null) conn.disconnect(); } }
    private void deletePanel(String authHeader, String panelId) { HttpURLConnection conn = null; try { conn = (HttpURLConnection) new URL(API_BASE+"/v2/panels/"+panelId).openConnection(); conn.setRequestMethod("DELETE"); conn.setRequestProperty("Authorization", authHeader); conn.getInputStream().close(); } catch (IOException ignored) {} finally { if (conn != null) conn.disconnect(); } }
    private void createPanel(String authHeader, JSONObject body) { HttpURLConnection conn = null; try { conn = (HttpURLConnection) new URL(API_BASE+"/v2/panels").openConnection(); conn.setRequestMethod("POST"); conn.setRequestProperty("Authorization", authHeader); conn.setRequestProperty("Content-Type","application/json"); conn.setDoOutput(true); try (Writer w = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) { w.write(body.toJSONString()); w.flush(); } conn.getResponseCode(); conn.getInputStream().close(); } catch (Exception ignored) {} finally { if (conn != null) conn.disconnect(); } }
    private static String readAll(InputStream stream) { if (stream == null) return ""; try { BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)); StringBuilder sb = new StringBuilder(); char[] buf = new char[8192]; int n; while ((n = br.read(buf)) != -1) sb.append(buf, 0, n); br.close(); return sb.toString(); } catch (IOException e) { return ""; } }
    public static final class PanelItem { private final String name, desc, command; public PanelItem(String n, String d, String c) { name=n; desc=d; command=c; } public String getName() { return name; } public String getDesc() { return desc; } public String getCommand() { return command; } }
}
