import java.io.File;
import java.nio.file.Files;

/** 真实网络端到端：WebCrawler.fetch 必应搜索页（中文关键词 + 链接保留全链路）。 */
public class FetchBing {
    public static void main(String[] args) throws Exception {
        cn.kerong.penguin.spigot.qqbind.WebCrawler wc =
            cn.kerong.penguin.spigot.qqbind.WebCrawler.INSTANCE;
        String result = wc.fetch("https://www.bing.com/search?q=我的世界 服务器", 10, 6000);
        boolean ok = !result.equals(cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE);
        System.out.println("== 抓取结果 " + (ok ? "成功" : "失败") + " ==");
        System.out.println(result.substring(0, Math.min(1200, result.length())));
        System.out.println("...");
        System.out.println("== 含搜索结果链接（多层入口可用）: " + (result.contains("https://") && result.contains("（")));
        Files.write(new File("/tmp/bing_fetch_result.txt").toPath(), result.getBytes("UTF-8"));
        System.exit(ok ? 0 : 1);
    }
}
