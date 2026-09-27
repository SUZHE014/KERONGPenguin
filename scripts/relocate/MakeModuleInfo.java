import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * 构造"命名模块版 kotlin-stdlib"复现 jar（模拟整合包中 KFF 提供的 kotlin.stdlib 模块）。
 *
 * 用法：java MakeModuleInfo <kotlin-stdlib.jar> <output.jar> <moduleName>
 * 在原 jar 基础上追加根 module-info.class（导出 jar 内全部 kotlin.* 包），
 * 使其从自动模块变为命名模块——与 KFF（Kotlin for Forge，经 Sinytra Connector
 * 载入）在 mods 目录中提供的 kotlin.stdlib 模块行为一致。
 */
public class MakeModuleInfo {
    public static void main(String[] args) throws Exception {
        String src = args[0];
        String out = args[1];
        String moduleName = args[2];

        Set<String> packages = new TreeSet<>();
        try (JarFile jf = new JarFile(src)) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                    int cut = n.lastIndexOf('/');
                    if (cut > 0) packages.add(n.substring(0, cut).replace('/', '.'));
                }
            }
        }

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor mv = cw.visitModule(moduleName, 0, null);
        mv.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        for (String pkg : packages) {
            if (pkg.equals("module-info")) continue;
            mv.visitExport(pkg.replace('.', '/'), 0);
        }
        mv.visitEnd();
        cw.visitEnd();
        byte[] moduleInfo = cw.toByteArray();

        try (JarFile srcJar = new JarFile(src);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(out))) {
            // module-info 必须是第一个条目
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(moduleInfo);
            jos.closeEntry();
            var entries = srcJar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.getName().equals("module-info.class")
                        || e.getName().equals("META-INF/MANIFEST.MF")) continue;
                jos.putNextEntry(new ZipEntry(e.getName()));
                try (var in = srcJar.getInputStream(e)) {
                    in.transferTo(jos);
                }
                jos.closeEntry();
            }
        }
        System.out.println("生成命名模块 jar: " + out + "（module " + moduleName
                + "，导出 " + packages.size() + " 个包）");
    }
}
