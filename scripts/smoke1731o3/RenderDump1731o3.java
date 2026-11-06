package scripts.smoke1731o3;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import scripts.smoke1731o.FakeRuntime;

/** 渲染展示值口径性能卡到 /tmp/perf_display.png（VLM 视觉复核用，一次性） */
public final class RenderDump1731o3 {
    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);
        PerformanceSnapshot snapshot = new PerformanceSnapshot(19.9, 5.0, 1024L, 2048L, 0.1, 3, 20);
        byte[] display = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "测试服", "12:00", snapshot, null, 666, 1000, true, null));
        try (FileOutputStream fos = new FileOutputStream("/tmp/perf_display.png")) {
            fos.write(display);
        }
        byte[] real = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "测试服", "12:00", snapshot, null, null, 20, false, null));
        try (FileOutputStream fos = new FileOutputStream("/tmp/perf_real.png")) {
            fos.write(real);
        }
        System.out.println("saved: /tmp/perf_display.png (" + Files.size(Path.of("/tmp/perf_display.png")) + "B), "
                + "/tmp/perf_real.png (" + Files.size(Path.of("/tmp/perf_real.png")) + "B)");
    }
}
