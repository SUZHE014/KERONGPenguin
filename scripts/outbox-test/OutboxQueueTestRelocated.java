import cn.huohuas001.bot.OutboxQueue;
import cn.huohuas001.shaded.kotlin.jvm.functions.Function0;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * OutboxQueue 功能验证——重定位版（1.5.4.5 NeoForge 线）。
 *
 * 与 scripts/outbox-test/OutboxQueueTest.java 同一用例集，区别：
 * 从**实际分发的 NeoForge 模组 JAR** 中加载重定位后的 OutboxQueue
 * （Function0 / Unit 等 kotlin 类型为 cn.huohuas001.shaded.* 命名空间），
 * 在独立 JVM 里验证发布产物内异步发送队列的真实语义：
 *  1. submit() 入队即返回（不随任务耗时阻塞调用线程）；
 *  2. 单线程 FIFO：任务按提交顺序串行执行；
 *  3. 相邻任务间隔 >= 最小发送间隔（250ms，容忍调度误差取 200ms）；
 *  4. 队列线程名 KERONGPenguin-QQ-Outbox 且 daemon；
 *  5. 任务异常不杀死队列；
 *  6. shutdown() 尽力清空后，新任务被静默丢弃。
 *
 * 运行（单文件源码模式，classpath 指向分发模组 JAR）：
 *   java -cp <KERONGPenguin_NeoForge jar> OutboxQueueTestRelocated.java
 */
public class OutboxQueueTestRelocated {

    static final List<String> order = new ArrayList<>();
    static final List<Long> times = new ArrayList<>();
    static volatile String workerThreadName;
    static volatile boolean workerDaemon;
    static CountDownLatch latch;
    static int failures = 0;

    static Function0<cn.huohuas001.shaded.kotlin.Unit> task(String id, long sleepMs, boolean boom) {
        return new Function0<cn.huohuas001.shaded.kotlin.Unit>() {
            @Override
            public cn.huohuas001.shaded.kotlin.Unit invoke() {
                try {
                    if (workerThreadName == null) {
                        Thread t = Thread.currentThread();
                        workerThreadName = t.getName();
                        workerDaemon = t.isDaemon();
                    }
                    if (boom) throw new RuntimeException("boom-" + id);
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                    synchronized (order) {
                        order.add(id);
                        times.add(System.currentTimeMillis());
                    }
                    return cn.huohuas001.shaded.kotlin.Unit.INSTANCE;
                } finally {
                    latch.countDown();
                }
            }
        };
    }

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        // 1+2+3+5：慢任务 / 爆炸任务 / 快任务依序入队，验证保序、间隔、线程属性
        latch = new CountDownLatch(3);
        long t0 = System.currentTimeMillis();
        OutboxQueue.INSTANCE.submit(task("A", 120, false));
        OutboxQueue.INSTANCE.submit(task("B", 0, true));   // 异常任务
        OutboxQueue.INSTANCE.submit(task("C", 0, false));
        long submitCost = System.currentTimeMillis() - t0;
        check(submitCost < 50, "submit() 入队即返回（调用线程零阻塞，耗时 " + submitCost + "ms < 50ms）");

        boolean done = latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        check(done, "队列任务全部完成（含异常任务不阻塞后续）");

        synchronized (order) {
            check(order.equals(List.of("A", "C")), "FIFO 保序（A,C 依序执行，实际 " + order + "）");
            if (times.size() == 2) {
                long gap = times.get(1) - times.get(0);
                check(gap >= 200, "相邻发送保留最小间隔（实测 " + gap + "ms >= 200ms 容忍值）");
            } else {
                check(false, "间隔检查需要 2 个成功任务");
            }
        }
        check("KERONGPenguin-QQ-Outbox".equals(workerThreadName),
                "队列线程名正确（实际 " + workerThreadName + "）");
        check(workerDaemon, "队列线程为 daemon（不阻止 JVM 退出）");

        // 6：shutdown 尽力清空（先入队一个慢任务再关）
        CountDownLatch drainLatch = new CountDownLatch(1);
        OutboxQueue.INSTANCE.submit(new Function0<cn.huohuas001.shaded.kotlin.Unit>() {
            @Override public cn.huohuas001.shaded.kotlin.Unit invoke() {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                drainLatch.countDown();
                return cn.huohuas001.shaded.kotlin.Unit.INSTANCE;
            }
        });
        long ts = System.currentTimeMillis();
        OutboxQueue.INSTANCE.shutdown();
        long shutdownCost = System.currentTimeMillis() - ts;
        check(drainLatch.await(0, java.util.concurrent.TimeUnit.SECONDS)
                || drainLatch.getCount() == 0, "停机时已排队消息被清空发送");
        check(shutdownCost <= 2500, "shutdown 不长时间阻塞关服（" + shutdownCost + "ms <= 2500ms）");

        // 停机后新任务被丢弃
        int before;
        synchronized (order) { before = order.size(); }
        OutboxQueue.INSTANCE.submit(task("D", 0, false));
        Thread.sleep(400);
        synchronized (order) {
            check(order.size() == before, "停机后新入队消息静默丢弃（不抛异常）");
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("=== OutboxQueue（分发 JAR 重定位版）功能验证全部通过 ===");
        } else {
            System.out.println("=== 失败 " + failures + " 项 ===");
            System.exit(1);
        }
    }
}
