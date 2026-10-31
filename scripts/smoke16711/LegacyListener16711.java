import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 1.6.7.11 冒烟专用：复刻 1.6.7.9 版 BindLoginGate 的旧式监听器
 * （@SubscribeEvent + 事件基类 Event 参数）。
 *
 * 编译进子类加载器（classpath 含 bus-7.2.0），用于负向对照：
 * 在按 FML 方式（markerType(IModBusEvent)）构造的 mod 总线上 register
 * 必须抛 IllegalArgumentException——即线上 1.6.7.9/1.6.7.10 的原始故障
 * 机制（"takes an argument that is not valid for this bus"），证明本测试
 * 环境具备与生产一致的 mod 总线校验语义。
 */
public class LegacyListener16711 {

    @SubscribeEvent
    public void onModBusEvent(Event event) {
        // 永不应当被调用（注册阶段即被拒绝）
    }
}
