import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * NeoForge 分发 JAR 第三方库重定位工具（split package 根治，1.5.4.3）。
 *
 * 背景：ModLauncher 把 mods 目录中每个 jar 都作为命名模块放进 TRANSFORMER/GAME 层，
 * 我们的 fat JAR 是自动模块（导出全部包）。当整合包中存在提供 kotlin 运行时的模块
 * （KFF 的 kotlin.stdlib，经 Sinytra Connector 载入）时，双方都导出 kotlin.* 包，
 * 模块解析直接 ResolutionException（实测复现：export package kotlin.jvm）。
 *
 * 方案：把可与其他模组/平台撞包的第三方库整体重定位到 cn/huohuas001/shaded/ 前缀下：
 *   - 类条目路径、目录条目路径同步重写；
 *   - 全部 .class 字节码经 ASM ClassRemapper 重写（常量池内所有类引用/描述符/签名）；
 *   - 方法体内 LDC 字符串常量（Class.forName 目标等）按斜杠/点号两种形态重写；
 *   - META-INF/services 声明（服务接口名 + 提供方行）同步重写；
 *   - 资源文件路径保持原位（非类条目不构成模块包，okhttp publicsuffix 等按原名加载）；
 *   - 不重定位 io.github.kloping（huhobot SDK，组件扫描按原始包名枚举，其他模组不会携带）
 *     与 com/mchange、com/zaxxer、org/quartz、org/terracotta、org/fusesource（服务端
 *     基础设施库，模组生态极少携带，且 quartz 配置键字符串重写有断连风险）。
 *
 * 用法：java RelocateTool <in.jar> <out.jar> <targetPrefix> <srcPrefix1,srcPrefix2,...>
 *   例：java RelocateTool merged.jar out.jar cn/huohuas001/shaded/ kotlin/,okhttp3/,okio/
 */
public class RelocateTool {

    static String targetPrefix;
    static final List<String> slashPrefixes = new ArrayList<>();
    static final List<Pattern> slashPatterns = new ArrayList<>();
    static final List<Pattern> dotPatterns = new ArrayList<>();

    static final Remapper REMAPPER = new Remapper() {
        @Override
        public String map(String internalName) {
            for (String p : slashPrefixes) {
                if (internalName.startsWith(p)) return targetPrefix + internalName;
            }
            return internalName;
        }
    };

    static int classesTotal = 0;
    static int classesRelocated = 0;
    static int ldcRemapped = 0;
    static int serviceLines = 0;
    static int versionsDropped = 0;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("用法: java RelocateTool <in.jar> <out.jar> <targetPrefix> <srcPrefixes>");
            System.exit(2);
        }
        String in = args[0];
        String out = args[1];
        targetPrefix = args[2].endsWith("/") ? args[2] : args[2] + "/";
        for (String p : args[3].split(",")) {
            String slash = p.trim().endsWith("/") ? p.trim() : p.trim() + "/";
            if (slash.isEmpty()) continue;
            slashPrefixes.add(slash);
            String comp = Pattern.quote(slash);
            slashPatterns.add(Pattern.compile("^" + comp + "([A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+$"));
            String dot = slash.replace('/', '.');
            dotPatterns.add(Pattern.compile("^" + Pattern.quote(dot) + "([A-Za-z0-9_$]+\\.)*[A-Za-z0-9_$]+$"));
        }

        Set<String> written = new HashSet<>();
        try (ZipFile zin = new ZipFile(in);
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out))) {

            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                byte[] data = read(zin, e);

                if (name.equals("module-info.class") || name.endsWith("/module-info.class")) {
                    continue; // 模块描述不进入重定位产物
                }
                if (name.startsWith("META-INF/versions/")) {
                    versionsDropped++;
                    continue; // Multi-Release 变体类：合并 jar 无 Multi-Release 标记，
                    // JVM 本就忽略；且各版本（BC versions/9、15、21）重定位代价大、
                    // 不处理会残留原始包名，直接丢弃（根路径同名类已重定位）。
                }

                String outName;
                if (name.startsWith("META-INF/services/") && !name.endsWith("/")) {
                    // 服务接口名（文件名）与提供方行（内容）同步重写
                    String svc = name.substring("META-INF/services/".length());
                    outName = "META-INF/services/" + remapDotted(svc);
                } else {
                    outName = mapPath(name, !name.endsWith(".class"));
                }

                if (name.endsWith(".class")) {
                    data = relocateClass(name, data);
                    classesTotal++;
                    if (!outName.equals(name)) classesRelocated++;
                } else if (name.startsWith("META-INF/services/") && !name.endsWith("/")) {
                    data = relocateServices(data);
                }

                if (!written.add(outName)) {
                    throw new IllegalStateException("重定位后条目冲突: " + outName);
                }
                ZipEntry oe = new ZipEntry(outName);
                oe.setTime(e.getTime());
                zout.putNextEntry(oe);
                zout.write(data);
                zout.closeEntry();
            }
        }

        // 自检：产物中不得残留任何重定位前缀下的 .class 条目
        int leftover = 0;
        try (ZipFile z = new ZipFile(out)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.endsWith(".class")) {
                    for (String p : slashPrefixes) {
                        if (n.startsWith(p)) {
                            leftover++;
                            System.err.println("残留未重定位类: " + n);
                            break;
                        }
                    }
                }
            }
        }
        System.out.println("重定位完成: " + out);
        System.out.println("  类文件: " + classesTotal + "（路径迁移 " + classesRelocated + "）");
        System.out.println("  LDC 字符串重写: " + ldcRemapped + " 处");
        System.out.println("  services 行重写: " + serviceLines + " 行");
        System.out.println("  META-INF/versions 丢弃: " + versionsDropped + " 条");
        System.out.println("  残留类检查: " + (leftover == 0 ? "通过（0 残留）" : "失败（" + leftover + " 残留）"));
        if (leftover > 0) System.exit(1);
    }

    /** 条目路径重写：类与目录条目重写，其余资源保持原位。 */
    static String mapPath(String name, boolean isNotClass) {
        // 目录条目：跟随重写（保持组件扫描对目录结构的依赖）；其他资源不重写
        if (isNotClass && !name.endsWith("/")) return name;
        for (String p : slashPrefixes) {
            if (name.startsWith(p)) return targetPrefix + name;
        }
        return name;
    }

    /** 单个类字节码重定位。 */
    static byte[] relocateClass(String sourceName, byte[] data) {
        ClassReader cr = new ClassReader(data);
        ClassWriter cw = new ClassWriter(0);
        ClassVisitor cv = new RelocatingClassRemapper(cw);
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /** services 声明重写：接口名（文件名由调用方处理）+ 提供方行。 */
    static byte[] relocateServices(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                sb.append(line);
            } else {
                String mapped = remapDotted(trimmed);
                if (!mapped.equals(trimmed)) serviceLines++;
                sb.append(mapped);
            }
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------- 字节码访问器 ----------

    static final class RelocatingClassRemapper extends ClassRemapper {
        RelocatingClassRemapper(ClassVisitor cv) {
            super(cv, REMAPPER);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor inner = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, inner) {
                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof String) {
                        String mapped = remapLdc((String) value);
                        if (mapped != value) ldcRemapped++;
                        value = mapped;
                    }
                    super.visitLdcInsn(value);
                }
            };
        }
    }

    // ---------- 字符串重写 ----------

    /** LDC 字符串重写：斜杠形态（okhttp3/internal/...）与点号形态（kotlin.jvm.X）。 */
    static String remapLdc(String s) {
        if (s == null || s.isEmpty()) return s;
        // 廉价预筛
        boolean maybe = false;
        for (String p : slashPrefixes) {
            if (s.startsWith(p)) { maybe = true; break; }
        }
        if (!maybe) {
            for (String p : slashPrefixes) {
                if (s.startsWith(p.replace('/', '.'))) { maybe = true; break; }
            }
        }
        if (!maybe) return s;

        for (Pattern pat : slashPatterns) {
            if (pat.matcher(s).matches()) return mapInternal(s);
        }
        return remapDotted(s);
    }

    static String remapDotted(String s) {
        for (Pattern pat : dotPatterns) {
            if (pat.matcher(s).matches()) {
                String internal = s.replace('.', '/');
                String mapped = mapInternal(internal);
                return mapped.replace('/', '.');
            }
        }
        return s;
    }

    static String mapInternal(String internalName) {
        return REMAPPER.map(internalName);
    }

    static byte[] read(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, (int) e.getSize()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
