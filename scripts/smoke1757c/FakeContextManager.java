import io.github.kloping.qqbot.entities.Bot;
import io.github.kloping.spt.interfaces.component.ContextManager;

/**
 * 冒烟专用 ContextManager：仅对 Bot.class 返回预设 Bot 实例。
 *
 * Starter.getBot() 实现为 contextManager.getContextEntity(Bot.class)
 * （Starter 无 bot 字段），注入这个实现即可让 QClient.starter.bot
 * 返回冒烟预设的官方信息，而不启动任何 spt 扫描 / WSS / HTTP。
 */
public final class FakeContextManager implements ContextManager {

    private final Bot bot;

    public FakeContextManager(Bot bot) {
        this.bot = bot;
    }

    @Override
    public int append(Object entity) {
        return 0;
    }

    @Override
    public int append(Object entity, String name) {
        return 0;
    }

    @Override
    public int append(Class<?> type, Object entity, String name) {
        return 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getContextEntity(Class<T> type) {
        return type == Bot.class ? (T) bot : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getContextEntity(Class<T> type, String name) {
        return getContextEntity(type);
    }
}
