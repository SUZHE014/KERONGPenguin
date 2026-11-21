import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * kloping SDK jansi 引用重定位（1.7.6.7 Arclight 覆盖轮）。
 *
 * 背景：Spigot 1.18+ PluginClassLoader 父优先委托——插件 fat JAR 内置库被
 * 服务端同包名类覆盖；Arclight-forge 1.20.1 自带 Forge 引导库 jansi 1.18
 * （无 Ansi.fgRgb(int)），SDK 五个类（两套 LoggerImpl、HttpClientConfig、
 * HttpClientManagerImpl+$Invoker）的 jansi 2.x 调用在 Arclight 上抛
 * NoSuchMethodError，QQ 客户端启动即死（表象：凭据自检通过但机器人离线）。
 *
 * 本工具把 base JAR（旧版可分发 JAR）里全部引用 org/fusesource/jansi/Ansi
 * 的 SDK 类重定位到插件自有的 cn/kerong/bot/compat/AnsiCompat（字节码级
 * 构建器替身，见该类 KDoc），产物供 package_jar*.py 作 base 使用。
 *
 * 用法：java -cp asm.jar:asm-commons.jar:tools-classes JansiRelocate <in.jar> <out.jar>
 *
 * 自检（防回归）：
 * 1. 重映射后字节里仍出现 org/fusesource/jansi → 立即失败（引用面超出已验证
 *    的 Ansi 方法集，需人工确认新引用形态）；
 * 2. 任一重映射类读取/写出失败 → 立即失败（不产出半成品 JAR）；
 * 3. 汇总输出重映射类清单与字节数对比，供打包日志核对。
 */
public final class JansiRelocate {

    /** jansi 唯一被 SDK 引用的类型（javap 枚举五类全部方法引用均在 Ansi 上）。 */
    private static final String FROM_TYPE = "org/fusesource/jansi/Ansi";
    private static final String TO_TYPE = "cn/kerong/bot/compat/AnsiCompat";

    /** 不动的 jansi 自身条目前缀（shaded jansi 2.x 原样保留）。 */
    private static final String JANSI_PREFIX = "org/fusesource/jansi/";

    /** 廉价字节预检标记（constant pool 中类引用的 UTF8 形态）。 */
    private static final byte[] NEEDLE;

    static {
        String s = "org/fusesource/jansi/Ansi";
        NEEDLE = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) NEEDLE[i] = (byte) s.charAt(i);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("用法: JansiRelocate <input.jar> <output.jar>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        if (!in.isFile()) throw new IllegalStateException("输入 JAR 不存在: " + in);
        File parent = out.getParentFile();
        if (parent != null) Files2.mkdirs(parent);

        List<String> remapped = new ArrayList<>();
        try (ZipFile zip = new ZipFile(in);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes = readAll(zip.getInputStream(entry));
                String name = entry.getName();

                boolean candidate = name.endsWith(".class")
                        && !name.startsWith(JANSI_PREFIX)
                        && !name.equals("module-info.class")
                        && !name.contains("/module-info.class")
                        && contains(bytes, NEEDLE);
                if (candidate) {
                    byte[] patched = remap(bytes, name);
                    // 自检 1：重映射后不得再出现 jansi 引用
                    if (contains(patched, NEEDLE)) {
                        throw new IllegalStateException(
                                "重映射后仍残留 " + FROM_TYPE + " 引用: " + name
                                        + "（引用面超出已验证范围，需人工扩展 AnsiCompat）");
                    }
                    bytes = patched;
                    remapped.add(name + " (" + entry.getSize() + " -> " + bytes.length + "B)");
                }
                ZipEntry outEntry = new ZipEntry(name);
                outEntry.setTime(entry.getTime());
                zos.putNextEntry(outEntry);
                zos.write(bytes);
                zos.closeEntry();
            }
        }
        if (remapped.isEmpty()) throw new IllegalStateException("未发现任何 jansi 引用类——base JAR 异常或已被处理过");
        System.out.println("重定位 " + FROM_TYPE + " -> " + TO_TYPE);
        for (String line : remapped) System.out.println("  REMAP " + line);
        System.out.println("共 " + remapped.size() + " 个类完成重定位: " + out);
    }

    /** ASM ClassRemapper：仅重命名 Ansi 类型引用（含方法描述符与栈映射帧）。 */
    private static byte[] remap(byte[] bytes, String name) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return FROM_TYPE.equals(internalName) ? TO_TYPE : null;
            }
        };
        try {
            reader.accept(new ClassRemapper(writer, remapper), 0);
        } catch (RuntimeException e) {
            throw new IllegalStateException("ASM 重映射失败: " + name + ": " + e, e);
        }
        return writer.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
        return buffer.toByteArray();
    }

    private JansiRelocate() {
    }

    /** 最小文件工具（避免 JDK11+ API 依赖，兼容 --release 8 编译）。 */
    static final class Files2 {
        static void mkdirs(File dir) {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IllegalStateException("无法创建目录: " + dir);
            }
        }
    }
}
