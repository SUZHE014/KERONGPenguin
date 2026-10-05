import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * NeoForge 1.5.5 增量补丁的字节链接修复工具（ASM 9.7）。
 *
 * 修补 Spigot 线构建产物（对 paper-api 编译）与 NeoForge 兼容层之间的
 * 四类链接差异，使其可原地插入 NeoForge 分发 JAR：
 *
 *  1. 类型重映射（皮肤链路）：
 *     com/destroystokyo/paper/profile/PlayerProfile → org/bukkit/entity/PlayerProfile
 *     org/bukkit/profile/PlayerTextures             → org/bukkit/entity/ProfileTextures
 *     经此映射后 Player.getPlayerProfile / PlayerProfile.getTextures /
 *     ProfileTextures.getSkin 的名字+描述符与兼容层完全一致（在线皮肤真实可用）。
 *
 *  2. 调用种类转换（invokeinterface ↔ invokevirtual）：
 *     以基底 JAR 里 org/bukkit/** 的真实形态（接口 / 类）为准——paper-api 的
 *     接口（PluginManager / BukkitScheduler / PlayerProfile / PlayerTextures 等）
 *     在兼容层实现为 Kotlin object / class 的，INVOKEINTERFACE 会抛
 *     IncompatibleClassChangeError，转换为 INVOKEVIRTUAL 后即正常链接。
 *     栈形完全一致（引用与 int 均占 1 槽），StackMapTable 无需重算。
 *
 *  3. BukkitScheduler.runTask 描述符对齐：
 *     paper-api 返回 BukkitTask，兼容层返回 Int（taskId）——调用方均丢弃
 *     返回值（javap 核实后随 pop），描述符改写为 (...)I 栈安全。
 *
 *  4. Kotlin 跨模块合成名：font$common_Bot → font$server_NeoForge
 *     （internal 顶层函数经 sourceSets 并入 server-NeoForge 模块后缀变化，
 *     声明类 InfoCardRenderer 在基底 JAR 中即 font$server_NeoForge）。
 *
 * 用法：java FixupTool <in.jar> <out.jar> <base.jar>
 * （base.jar = 官网 1.5.4.5 分发模组，仅读取其中 org/bukkit/** 的类形态）
 */
public class FixupTool {

    private static final Map<String, String> TYPE_MAP = Map.of(
            "com/destroystokyo/paper/profile/PlayerProfile", "org/bukkit/entity/PlayerProfile",
            "org/bukkit/profile/PlayerTextures", "org/bukkit/entity/ProfileTextures");

    private static final String FONT_FROM = "font$common_Bot";
    private static final String FONT_TO = "font$server_NeoForge";

    /** 兼容层里以「类」形态存在的 org/bukkit 内部名（来自基底 JAR 实测）。 */
    private static final Set<String> classShapes = new HashSet<>();
    /** 兼容层里以「接口」形态存在的 org/bukkit 内部名。 */
    private static final Set<String> interfaceShapes = new HashSet<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("用法: java FixupTool <in.jar> <out.jar> <base.jar>");
            System.exit(2);
        }
        loadShapes(args[2]);

        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                String mapped = TYPE_MAP.get(internalName);
                return mapped != null ? mapped : internalName;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (FONT_FROM.equals(name)
                        && owner.startsWith("cn/huohuas001/huhobotPenguin/spigot/render/")) {
                    return FONT_TO;
                }
                return name;
            }
        };

        int processed = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) {
                    ClassReader cr = new ClassReader(zin.readAllBytes());
                    ClassWriter cw = new ClassWriter(0);
                    // 链：opcode/描述符修复（看到重映射前的值）→ ClassRemapper → Writer
                    ClassVisitor fixer = new ClassVisitor(Opcodes.ASM9, new ClassRemapper(cw, remapper)) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            MethodVisitor inner = super.visitMethod(access, name, descriptor,
                                                                    signature, exceptions);
                            return new MethodVisitor(Opcodes.ASM9, inner) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String name,
                                                            String descriptor, boolean isInterface) {
                                    int op = opcode;
                                    boolean itf = isInterface;
                                    // 3. runTask：兼容层返回 Int（调用方丢弃返回值，pop 栈安全）
                                    if ("org/bukkit/scheduler/BukkitScheduler".equals(owner)
                                            && "runTask".equals(name)
                                            && descriptor.equals("(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;")) {
                                        descriptor = "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)I";
                                        op = Opcodes.INVOKEVIRTUAL;
                                        itf = false;
                                    } else {
                                        String mappedOwner = remapper.map(owner);
                                        // 2. 调用种类与兼容层真实形态对齐（仅 org/bukkit 命名空间）
                                        if (op == Opcodes.INVOKEINTERFACE
                                                && classShapes.contains(mappedOwner)) {
                                            op = Opcodes.INVOKEVIRTUAL;
                                            itf = false;
                                        } else if (op == Opcodes.INVOKEVIRTUAL
                                                && interfaceShapes.contains(mappedOwner)) {
                                            op = Opcodes.INVOKEINTERFACE;
                                            itf = true;
                                        }
                                    }
                                    super.visitMethodInsn(op, owner, name, descriptor, itf);
                                }
                            };
                        }
                    };
                    cr.accept(fixer, 0);
                    zout.putNextEntry(new ZipEntry(entry.getName()));
                    zout.write(cw.toByteArray());
                    processed++;
                } else {
                    zout.putNextEntry(new ZipEntry(entry.getName()));
                    int n;
                    while ((n = zin.read(buffer)) > 0) {
                        zout.write(buffer, 0, n);
                    }
                }
            }
        }
        System.out.println("FixupTool 完成: " + processed + " 个类（形态目录: "
                + classShapes.size() + " 类 / " + interfaceShapes.size() + " 接口）");
    }

    /** 从基底 JAR 读取 org/bukkit/** 的类形态（访问标志）。 */
    private static void loadShapes(String baseJar) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(baseJar))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.endsWith(".class") || !name.startsWith("org/bukkit/")) {
                    continue;
                }
                ClassReader cr = new ClassReader(zin.readAllBytes());
                boolean isInterface = (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0;
                String internal = name.substring(0, name.length() - ".class".length());
                if (isInterface) {
                    interfaceShapes.add(internal);
                } else {
                    classShapes.add(internal);
                }
            }
        }
        if (classShapes.isEmpty()) {
            throw new IllegalStateException("基底 JAR 未找到 org/bukkit 兼容类");
        }
    }
}
