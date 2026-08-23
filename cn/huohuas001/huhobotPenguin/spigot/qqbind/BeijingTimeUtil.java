package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 联网获取北京时间工具
 *
 * 异步从多个时间 API 获取北京时间（UTC+8），
 * 不依赖服务器本地时间。
 */
public final class BeijingTimeUtil {
    private static final TimeZone BEIJING_TZ = TimeZone.getTimeZone("Asia/Shanghai");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BeijingTimeFetcher");
        t.setDaemon(true);
        return t;
    });
    private static volatile String cachedDate = "";
    private static volatile long cachedAt = 0;
    private static final long CACHE_TTL = 5 * 60 * 1000;

    private BeijingTimeUtil() {}

    /**
     * 获取北京当前日期（yyyy-MM-dd）。
     * 异步联网获取，最多等待 3 秒；超时则用上次缓存或服务器时间（Asia/Shanghai 时区）兜底。
     */
    public static String getBeijingDate() {
        long now = System.currentTimeMillis();
        if (!cachedDate.isEmpty() && (now - cachedAt) < CACHE_TTL) {
            return cachedDate;
        }
        String date = fetchOnlineDate();
        if (date == null || date.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setTimeZone(BEIJING_TZ);
            date = sdf.format(new Date());
        }
        cachedDate = date;
        cachedAt = now;
        return date;
    }

    /**
     * 计算给定日期的次日（yyyy-MM-dd）。
     */
    public static String nextDay(String date) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setTimeZone(BEIJING_TZ);
            Date d = sdf.parse(date);
            Calendar cal = Calendar.getInstance(BEIJING_TZ);
            cal.setTime(d);
            cal.add(Calendar.DAY_OF_MONTH, 1);
            return sdf.format(cal.getTime());
        } catch (Throwable t) {
            return date;
        }
    }

    private static String fetchOnlineDate() {
        String[] apis = {
            "http://worldtimeapi.org/api/timezone/Asia/Shanghai",
            "http://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
        };
        for (String api : apis) {
            try {
                Future<String> f = EXECUTOR.submit(new Callable<String>() {
                    public String call() throws Exception {
                        return fetchFromApi(api);
                    }
                });
                String result = f.get(3, TimeUnit.SECONDS);
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            } catch (Throwable t) {
                // try next api
            }
        }
        return null;
    }

    private static String fetchFromApi(String apiUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
            br.close();
            String body = sb.toString();
            if (apiUrl.contains("worldtimeapi")) {
                int idx = body.indexOf("\"datetime\":\"");
                if (idx >= 0) {
                    int start = idx + 12;
                    int end = body.indexOf("\"", start);
                    if (end > start) {
                        String datetime = body.substring(start, end);
                        if (datetime.length() >= 10) {
                            return datetime.substring(0, 10);
                        }
                    }
                }
            } else if (apiUrl.contains("taobao")) {
                int idx = body.indexOf("\"data\":");
                if (idx >= 0) {
                    int start = body.indexOf("\"", idx + 7);
                    int end = body.indexOf("\"", start + 1);
                    if (end > start && end - start >= 13) {
                        String ts = body.substring(start + 1, end);
                        if (ts.length() >= 13) {
                            long millis = Long.parseLong(ts.substring(0, 13));
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                            sdf.setTimeZone(BEIJING_TZ);
                            return sdf.format(new Date(millis));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
