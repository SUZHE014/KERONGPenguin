import cn.kerong.bot.GroupAtTracker;
import cn.kerong.bot.RecentMessageGate;
import cn.kerong.bot.tools.QqText;

/**
 * 1.6.7.5 AI 聊天艾特判定修复的逻辑冒烟：
 * 1. GroupAtTracker：记录 / 查询 / 空值防呆；
 * 2. RecentMessageGate：同一 id 只放行一次、不同 id 各自放行、空 id 恒放行；
 * 3. QqText.stripLeadingMentionPlaceholders：行首 <@id> / <@!id>（多个连续）
 *    剥离、正文中间不剥、无片段零改动；
 * 4. QqText.removeMentionPlaceholders：全文剔除（AI 正文清理）。
 *
 * 编译运行（仓库根目录）：
 *   javac -cp common-Bot/build/libs/common-Bot.jar \
 *     -d scripts/smoke1675/out scripts/smoke1675/AiChatAtTest.java
 *   java -cp scripts/smoke1675/out:common-Bot/build/libs/common-Bot.jar:\
 *     common-Bot/libs/deps.jar AiChatAtTest
 */
public class AiChatAtTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok || detail.isEmpty() ? "" : "  [" + detail + "]"));
    }

    public static void main(String[] args) {
        // ---- 1. GroupAtTracker ----
        check("未记录 → isAtBot=false", !GroupAtTracker.INSTANCE.isAtBot("m1"), "");
        GroupAtTracker.INSTANCE.recordAtBot("m1");
        check("记录后 → isAtBot=true", GroupAtTracker.INSTANCE.isAtBot("m1"), "");
        check("其他 id → false", !GroupAtTracker.INSTANCE.isAtBot("m2"), "");
        check("null id → false", !GroupAtTracker.INSTANCE.isAtBot(null), "");
        GroupAtTracker.INSTANCE.recordAtBot(null);
        GroupAtTracker.INSTANCE.recordAtBot("");
        check("null/空 记录不抛异常", true, "");

        // ---- 2. RecentMessageGate ----
        check("首次 tryAcquire(mA)=true", RecentMessageGate.INSTANCE.tryAcquire("mA"), "");
        check("重复 tryAcquire(mA)=false", !RecentMessageGate.INSTANCE.tryAcquire("mA"), "");
        check("再次重复仍 false", !RecentMessageGate.INSTANCE.tryAcquire("mA"), "");
        check("不同 id (mB) 放行", RecentMessageGate.INSTANCE.tryAcquire("mB"), "");
        check("null id 恒放行（不去重）", RecentMessageGate.INSTANCE.tryAcquire(null), "");
        check("null id 再次放行", RecentMessageGate.INSTANCE.tryAcquire(null), "");
        check("空串 id 恒放行", RecentMessageGate.INSTANCE.tryAcquire(""), "");

        // ---- 3. stripLeadingMentionPlaceholders ----
        String s1 = QqText.INSTANCE.stripLeadingMentionPlaceholders("<@ABC123> /查性能");
        check("群格式行首剥离", s1.equals("/查性能"), "'" + s1 + "'");
        String s2 = QqText.INSTANCE.stripLeadingMentionPlaceholders("<@!ABC123> 你好");
        check("频道格式行首剥离", s2.equals("你好"), "'" + s2 + "'");
        String s3 = QqText.INSTANCE.stripLeadingMentionPlaceholders("  <@A1> <@!B2>   /查在线");
        check("多个连续 + 前导空白", s3.equals("/查在线"), "'" + s3 + "'");
        String s4 = QqText.INSTANCE.stripLeadingMentionPlaceholders("你好 <@A1> 世界");
        check("正文中间不剥", s4.equals("你好 <@A1> 世界"), "'" + s4 + "'");
        String plain = "/查信息 普通消息";
        check("无片段原样返回（同一实例）", QqText.INSTANCE.stripLeadingMentionPlaceholders(plain) == plain, "");
        String only = QqText.INSTANCE.stripLeadingMentionPlaceholders("<@A1>");
        check("仅占位 → 空", only.isEmpty(), "'" + only + "'");
        String nl = QqText.INSTANCE.stripLeadingMentionPlaceholders("<@A1>\n/绑定 123456");
        check("占位后换行也剥", nl.equals("/绑定 123456"), "'" + nl + "'");

        // ---- 4. removeMentionPlaceholders ----
        String r1 = QqText.INSTANCE.removeMentionPlaceholders("你好 <@A1> 再 <@!B2> 见");
        check("全文剔除所有占位", r1.equals("你好  再  见"), "'" + r1 + "'");
        check("全文剔除-无占位原样（同一实例）", QqText.INSTANCE.removeMentionPlaceholders("普通文本") == "普通文本", "");

        System.out.println("---- 通过 " + pass + " / 失败 " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
