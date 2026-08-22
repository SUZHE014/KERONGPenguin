package cn.huohuas001.huhobotPenguin.spigot.qqbind;
import com.alibaba.fastjson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class AiChat {
    private AiChat() {}

    public static String chat(String userMessage, QqBindManager mgr, String groupId, String userId) {
        if (mgr == null) return "AI 对话未就绪。";
        if (!mgr.isAiEnabled()) return "AI 对话未开启。";
        String baseUrl = mgr.getAiBaseUrl();
        String apiKey = mgr.getAiApiKey();
        String model = mgr.getAiModel();
        String systemPrompt = mgr.getAiSystemPrompt();
        if (baseUrl == null || baseUrl.isEmpty() || apiKey == null || apiKey.isEmpty()) return "AI 对话未配置。";

        boolean ctxOn = mgr.isAiContextEnabled(groupId, userId);
        QqBindManager.logVerbose("[AI对话] 输入: " + userMessage + " 上下文=" + ctxOn);

        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (mgr.isAiUrlAutoAppend() && !url.endsWith("/chat/completions")) url = url + "/chat/completions";

        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
            if (ctxOn) {
                List<QqBindManager.ChatMessage> hist = mgr.getAiContext(groupId, userId);
                if (hist != null) {
                    for (int i = 0; i < hist.size(); i++) {
                        QqBindManager.ChatMessage cm = hist.get(i);
                        JSONObject hm = new JSONObject();
                        hm.put("role", cm.role);
                        hm.put("content", cm.content);
                        messages.add(hm);
                    }
                }
            }
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);
            body.put("messages", messages);
            body.put("temperature", 0.7);

            QqBindManager.logVerbose("[AI对话] 请求 URL=" + url + " 消息数=" + messages.size());
            String resp = doPost(url, apiKey, body.toJSONString());
            QqBindManager.logVerbose("[AI对话] 响应: " + (resp.length() > 300 ? resp.substring(0, 300) + "..." : resp));
            JSONObject json = JSON.parseObject(resp);
            if (json == null) return "AI 返回为空";
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                String err = json.getString("error");
                return "AI 返回无 choices" + (err != null ? ": " + err : "");
            }
            String result = choices.getJSONObject(0).getJSONObject("message").getString("content");
            result = result != null ? result.trim() : "（AI 未返回内容）";
            if (ctxOn) mgr.appendAiContext(groupId, userId, userMessage, result);
            return result;
        } catch (Throwable t) {
            return "AI 调用失败：" + t.getMessage();
        }
    }

    private static String doPost(String urlStr, String apiKey, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream(); Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                w.write(body);
                w.flush();
            }
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            BufferedReader br = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
            br.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
