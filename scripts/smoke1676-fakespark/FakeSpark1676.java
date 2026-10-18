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
 * 真实形态假 spark（1.6.7.5 冒烟核心）。
 *
 * 与 1.6.7.3/1674 冒烟 FakeSpark 的本质差别：统计实现类全部为
 * **包私有静态类**——真实 spark 的实现（me.lucko.spark.common.*）正是
 * 包私有，1.6.7.2~1.6.7.4 的 SparkBridge 对实现类取 Method 反射调用
 * 抛 IllegalAccessException 被逐项吞掉，导致「装了 spark 也全指标读不到」。
 * 本类在独立 URLClassLoader 内装配（模拟 Bukkit 插件类加载器隔离）。
 *
 * SparkProvider.set 为包私有（对齐真实实现）——install 用反射注入。
 */
public class FakeSpark1676 implements Spark {

    public static void install() throws Exception {
        Method set = SparkProvider.class.getDeclaredMethod("set", Spark.class);
        set.setAccessible(true);
        set.invoke(null, new FakeSpark1676());
    }

    // ---- 包私有统计实现（真实 spark 同款可见性） ----

    static class TpsStat implements DoubleStatistic<StatisticWindow.TicksPerSecond> {
        public String name() { return "tps"; }
        public StatisticWindow.TicksPerSecond[] getWindows() {
            return StatisticWindow.TicksPerSecond.values();
        }
        public double poll(StatisticWindow.TicksPerSecond w) { return 19.87; }
        public double[] poll() { return new double[]{19.87, 19.9, 19.6}; }
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
    // 1.6.7.6：playerPing 统计实现（包私有，真实 spark 同款可见性）
    static class PingStat implements GenericStatistic<DoubleAverageInfo, StatisticWindow.PlayerPing> {
        public String name() { return "playerPing"; }
        public StatisticWindow.PlayerPing[] getWindows() {
            return StatisticWindow.PlayerPing.values();
        }
        public DoubleAverageInfo poll(StatisticWindow.PlayerPing w) { return new AvgInfo(); }
        public DoubleAverageInfo[] poll() { return new AvgInfo[0]; }
    }

    public Map<String, me.lucko.spark.api.gc.GarbageCollector> gc() { return Collections.emptyMap(); }
    public me.lucko.spark.api.placeholder.PlaceholderResolver placeholders() { return null; }
}
