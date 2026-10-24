import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/**
 * 真实形态假 spark（1.6.7.7 冒烟：TPS 异常值 300+ 封顶回归）。
 *
 * 与 1676 版差别：TpsStat#poll 返回 300.0——模拟「Spigot 系 recentTps 无上限
 * 瞬时平均」类异常数据源（用户实测 /查性能 TPS 飙到 300+）；SparkBridge
 * 1.6.7.7 的 pollTps 出口封顶应把它压到 20.0。
 * 其余统计实现保持 1676 形态（包私有类 + 公共接口方法，真实 spark 同款
 * 可见性），playerPing mean 改 42.5。
 */
public class FakeSpark1677 implements Spark {

    public static void install() throws Exception {
        Method set = SparkProvider.class.getDeclaredMethod("set", Spark.class);
        set.setAccessible(true);
        set.invoke(null, new FakeSpark1677());
    }

    // ---- 包私有统计实现（真实 spark 同款可见性） ----

    static class TpsStat implements DoubleStatistic<StatisticWindow.TicksPerSecond> {
        public String name() { return "tps"; }
        public StatisticWindow.TicksPerSecond[] getWindows() {
            return StatisticWindow.TicksPerSecond.values();
        }
        // 1.6.7.7：异常源模拟——真实 Spigot 系 recentTps 无上限时会出现这种值
        public double poll(StatisticWindow.TicksPerSecond w) { return 300.0; }
        public double[] poll() { return new double[]{300.0, 287.5, 260.0}; }
    }

    static class MsptStat implements GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> {
        public String name() { return "mspt"; }
        public StatisticWindow.MillisPerTick[] getWindows() {
            return StatisticWindow.MillisPerTick.values();
        }
        public DoubleAverageInfo poll(StatisticWindow.MillisPerTick w) { return new AvgInfo(); }
        public DoubleAverageInfo[] poll() { return new AvgInfo[0]; }
    }

    static class AvgInfo implements DoubleAverageInfo {
        public double mean() { return 12.4; }
        public double max() { return 30.1; }
        public double min() { return 2.2; }
        public double median() { return 11.8; }
        public double percentile95th() { return 28.9; }
        public double percentile(double p) { return 12.0; }
    }

    static class CpuStat implements DoubleStatistic<StatisticWindow.CpuUsage> {
        public String name() { return "cpuProcess"; }
        public StatisticWindow.CpuUsage[] getWindows() {
            return StatisticWindow.CpuUsage.values();
        }
        public double poll(StatisticWindow.CpuUsage w) { return 0.234; }
        public double[] poll() { return new double[]{0.234, 0.31, 0.29}; }
    }

    public DoubleStatistic<StatisticWindow.TicksPerSecond> tps() { return new TpsStat(); }
    public DoubleStatistic<StatisticWindow.CpuUsage> cpuSystem() { return new CpuStat(); }
    public DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess() { return new CpuStat(); }
    public GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt() { return new MsptStat(); }
    public GenericStatistic<DoubleAverageInfo, StatisticWindow.MemoryAllocation> memoryAllocation() {
        throw new UnsupportedOperationException("not part of the bridge");
    }
    public GenericStatistic<DoubleAverageInfo, StatisticWindow.PlayerPing> playerPing() {
        return new PingStat();
    }
    static class PingStat implements GenericStatistic<DoubleAverageInfo, StatisticWindow.PlayerPing> {
        public String name() { return "playerPing"; }
        public StatisticWindow.PlayerPing[] getWindows() {
            return StatisticWindow.PlayerPing.values();
        }
        public DoubleAverageInfo poll(StatisticWindow.PlayerPing w) { return new PingInfo(); }
        public DoubleAverageInfo[] poll() { return new DoubleAverageInfo[0]; }
    }
    static class PingInfo implements DoubleAverageInfo {
        public double mean() { return 42.5; }
        public double max() { return 80.0; }
        public double min() { return 10.0; }
        public double median() { return 40.0; }
        public double percentile95th() { return 75.0; }
        public double percentile(double p) { return 42.0; }
    }

    public Map<String, me.lucko.spark.api.gc.GarbageCollector> gc() { return Collections.emptyMap(); }
    public me.lucko.spark.api.placeholder.PlaceholderResolver placeholders() { return null; }
}
