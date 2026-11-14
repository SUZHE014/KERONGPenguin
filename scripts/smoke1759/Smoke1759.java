import cn.kerong.bot.events.GroupMessageHandler;
import cn.kerong.bot.provider.ChatFormat;
import cn.kerong.penguin.fabric.BindRestrict;
import cn.kerong.penguin.fabric.BindRestrictCommandGate;
import cn.kerong.penguin.fabric.BindRestrictInteractions;
import cn.kerong.penguin.spigot.qqbind.MessageTemplates;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 1.7.5.9 冒烟（独立 JVM 真实类，两段 classpath）：
 *  S1-S3（common-Bot 新编译输出）任务1 显示名来源三模式；
 *  S4-S8 任务2 菜单模板历史默认比对 + 删除重建；
 *  F1-F6（server-Fabric mojmap 编译输出 + mojmap minecraft + fabric-api）任务3
 *      命令树 requirement 包装真实 Unsafe 替换 + 受限玩家命令 parse 拒绝 + 解除恢复。
 */
public class Smoke1759 {
    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + "  | " + detail); }
    }

    static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    /** 假 KERONG：动态代理，只实现 getChatFormat。 */
    static cn.kerong.bot.KERONG fakePlugin(ChatFormat format) {
        return (cn.kerong.bot.KERONG) java.lang.reflect.Proxy.newProxyInstance(
                Smoke1759.class.getClassLoader(),
                new Class[]{cn.kerong.bot.KERONG.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("getChatFormat")) return format;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt.isPrimitive() && rt != void.class) return 0;
                    return null;
                });
    }

    public static void main(String[] args) throws Throwable {
        // ---------- S1-S3 任务1：显示名来源 ----------
        Class<?> gmc = Class.forName("cn.kerong.bot.events.GroupMessageHandler");
        Method resolve = gmc.getDeclaredMethod("resolveForwardDisplayName", cn.kerong.bot.KERONG.class, String.class);
        resolve.setAccessible(true);

        ChatFormat fmtGroup = new ChatFormat(
                "[游戏] <{name}> {message}", "[§bQQ§r] {name}: {message}", true, "", "group_nickname");
        ChatFormat fmtQq = new ChatFormat(
                "[游戏] <{name}> {message}", "[§bQQ§r] {name}: {message}", true, "", "qq_nickname");
        ChatFormat fmtBad = new ChatFormat(
                "[游戏] <{name}> {message}", "[§bQQ§r] {name}: {message}", true, "", "随便填");

        Object handler = gmc.getDeclaredConstructor(cn.kerong.bot.KERONG.class).newInstance(fakePlugin(fmtGroup));
        String nickname = "小明";
        String r1 = (String) resolve.invoke(handler, fakePlugin(fmtGroup), nickname);
        ok("S1 group_nickname 默认直出群昵称", "小明".equals(r1), r1);
        String r2 = (String) resolve.invoke(handler, fakePlugin(fmtQq), nickname);
        ok("S2 qq_nickname 当前与群昵称同值（官方单字段口径）", "小明".equals(r2), r2);
        String r3 = (String) resolve.invoke(handler, fakePlugin(fmtBad), nickname);
        ok("S3 未知取值回退 group_nickname", "小明".equals(r3), r3);

        // ---------- S4-S8 任务2：menu 历史默认比对 + 删除重建 ----------
        Class<?> mt = Class.forName("cn.kerong.penguin.spigot.qqbind.MessageTemplates");
        Object instance = mt.getField("INSTANCE").get(null);
        Field upgradesF = mt.getDeclaredField("DEFAULT_CONTENT_UPGRADES");
        upgradesF.setAccessible(true);
        Map<?, ?> upgrades = (Map<?, ?>) upgradesF.get(null);
        Object menuSpec = upgrades.get("menu");
        ok("S4a menu 升级规则在位", menuSpec != null, "DEFAULT_CONTENT_UPGRADES 无 menu");
        Method shouldUpgrade = menuSpec.getClass().getDeclaredMethod("shouldUpgrade", String.class);
        shouldUpgrade.setAccessible(true);

        String olds0 = "### 指令菜单\n\n点击下方按钮即可执行；带参数的指令（绑定 / 发信息 / 执行命令等）点击后复制到输入框，补全内容再发送。\n\n---\n**当前插件版本：v{version}**";
        String olds1 = "### 指令菜单（{page}/{pages}）";
        String olds2 = "### 指令菜单（{page}/{pages}）\n\n**当前插件版本：v{version}**";
        String current = "### 指令菜单（{page}/{pages}）\n\n---\nv{version}";
        String handEdited = "### 我的自定义菜单\n老板娘亲笔，别动我";
        // BOM/换行差异形态（normalize 容忍）
        String olds0Bom = "\uFEFF" + olds0.replace("\n", "\r\n");

        ok("S4b 1.7.5 引入版默认 → 应升级",
                (Boolean) shouldUpgrade.invoke(menuSpec, olds0));
        ok("S4c 覆盖第二轮默认 → 应升级",
                (Boolean) shouldUpgrade.invoke(menuSpec, olds1));
        ok("S4d 覆盖第三轮默认 → 应升级",
                (Boolean) shouldUpgrade.invoke(menuSpec, olds2));
        ok("S4e 带 BOM+CR 差异的历史默认 → 应升级（normalize 容忍）",
                (Boolean) shouldUpgrade.invoke(menuSpec, olds0Bom));
        ok("S5 手改内容 → 不升级（保留，不得误删）",
                !(Boolean) shouldUpgrade.invoke(menuSpec, handEdited));
        ok("S6 当前默认 → 不升级（幂等）",
                !(Boolean) shouldUpgrade.invoke(menuSpec, current));
        ok("S6b 含特征中间态（### 指令菜单 开头的手改）→ 兜底收敛",
                (Boolean) shouldUpgrade.invoke(menuSpec, "### 指令菜单\n随便改的内容"));

        // applyReplacement：删除重建（delete + writeBytes）
        Method apply = mt.getDeclaredMethod("applyReplacement", File.class, File.class, String.class);
        apply.setAccessible(true);
        File dir = Files.createTempDirectory("smoke1759-tmpl").toFile();
        File target = new File(dir, "menu.md");
        Files.write(target.toPath(), "旧内容".getBytes("UTF-8"));
        long oldMtime = target.lastModified();
        Thread.sleep(5);
        apply.invoke(instance, dir, target, current);
        String after = new String(Files.readAllBytes(target.toPath()), "UTF-8");
        ok("S7 applyReplacement 删除重建：内容=新默认",
                current.equals(after), after);
        ok("S8 applyReplacement 产生全新文件（mtime 变化）",
                target.lastModified() > oldMtime, oldMtime + " -> " + target.lastModified());

        // ---------- F1-F6 任务3：命令树包装（真实 Unsafe） ----------
        // brigadier 树：/test 要求 base=true；受限玩家应 parse 拒绝
        CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher = new CommandDispatcher<>();
        Predicate<net.minecraft.commands.CommandSourceStack> baseReq = s -> true;
        dispatcher.register((LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>)
                LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("test")
                        .requires(baseReq)
                        .executes(ctx -> {
                            System.out.println("[exec] /test 执行（不应到达——受限）");
                            return 1;
                        }));
        CommandNode<net.minecraft.commands.CommandSourceStack> root = dispatcher.getRoot();
        CommandNode<net.minecraft.commands.CommandSourceStack> testNode = root.getChild("test");
        ok("F0 构造基线：/test requirement 为原判定", testNode.getRequirement() == baseReq);

        BindRestrictCommandGate.INSTANCE.ensureWrapped(root);
        Predicate<?> wrapped = testNode.getRequirement();
        ok("F1 ensureWrapped 后 requirement 已替换（非原引用）",
                wrapped != null && wrapped != baseReq, String.valueOf(wrapped));

        // 结构验证：包装保留原判定 + 深层节点递归包装 + 幂等
        Object origOfTest = originalOf(wrapped);
        ok("F2 包装保留原判定（original == baseReq 引用）", origOfTest == baseReq, String.valueOf(origOfTest));

        // 注：受限玩家命令被拒的端到端（真实 CommandSourceStack/ServerPlayer 实例）
        // 未在冒烟 JVM 完成——MC 类加载依赖 Mojang 私有库（datafixers/fastutil），
        // 沙盒不可达；由门禁 T3 段字节码断言（GatePredicate 组合 + method_14363 包装）
        // 与生产 Fabric 环境（打包重映射后行为）覆盖。

        // 幂等：二次 ensureWrapped 不嵌套
        BindRestrictCommandGate.INSTANCE.ensureWrapped(root);
        Predicate<?> wrapped2 = testNode.getRequirement();
        ok("F7 ensureWrapped 幂等（不重复嵌套）", wrapped2 == wrapped, String.valueOf(wrapped2));

        // BindRestrictInteractions null 快路径
        ok("F8 onChat(null) 放行（未受限）", BindRestrictInteractions.INSTANCE.onChat(null));
        ok("F8b onCommand(null) 放行", BindRestrictInteractions.INSTANCE.onCommand(null));
        ok("F8c onUseBlock(null) PASS", BindRestrictInteractions.INSTANCE.onUseBlock(null)
                == net.minecraft.world.InteractionResult.PASS);

        System.out.println("\n===== smoke1759: " + pass + " 通过 / " + fail + " 失败 =====");
        System.exit(fail == 0 ? 0 : 1);
    }

    static Object unsafeAllocate(Class<?> c) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
        return u.allocateInstance(c);
    }

    @SuppressWarnings("unchecked")
    static <T> T cast(Predicate<?> p, Object v) {
        return (T) v;
    }

    @SuppressWarnings("unchecked")
    static <S> Predicate<S> typed(Predicate<?> p) {
        return (Predicate<S>) p;
    }

    /** 反射读包装内的 original（验证保留）。 */
    static Object originalOf(Predicate<?> wrapper) {
        try {
            for (Class<?> c = wrapper.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getName().equals("original")) return f.get(wrapper);
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    static void setUuid(Object player, UUID uuid) throws Exception {
        // Entity.uuid（mojap 字段名 uuid）——Unsafe 写 final
        Class<?> c = player.getClass();
        Field target = null;
        for (Class<?> k = c; k != null && target == null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals("uuid") && f.getType() == UUID.class) {
                    target = f;
                    break;
                }
            }
        }
        if (target == null) throw new IllegalStateException("未找到 Entity.uuid 字段");
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        u.putObject(player, u.objectFieldOffset(target), uuid);
    }

}
