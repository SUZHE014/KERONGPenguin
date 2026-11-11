import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 合并映射：intermediary tiny（official[混淆名] ↔ intermediary）+ Mojang 官方
 * ProGuard 映射 client.txt（mojang ↔ 混淆名）→ 三列 tiny v2
 * （official / intermediary / mojang），供 tiny-remapper 在任意两命名空间间重映射。
 *
 * 用法：MergeMappings <intermediary.tiny> <client.txt> <merged.tiny>
 */
public final class MergeMappings {
    public static void main(String[] args) throws Exception {
        MemoryMappingTree tree = new MemoryMappingTree();
        // 先读 ProGuard（src=mojang，dst=official[混淆名]）建树，
        // 再读 intermediary tiny（src=official，dst=intermediary）——incoming
        // srcNs 与树的 dstNs 对齐即按混淆名合并，补出 intermediary 列
        try (Reader r = Files.newBufferedReader(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            ProGuardFileReader.read(r, "mojang", "official", tree);
        }
        try (Reader r = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            Tiny2FileReader.read(r, tree);
        }
        try (Writer w = Files.newBufferedWriter(Paths.get(args[2]), StandardCharsets.UTF_8)) {
            Tiny2FileWriter writer = new Tiny2FileWriter(w, true);
            tree.accept(writer);
            writer.close();
        }
        System.out.println("合并完成: " + args[2]
                + " srcNs=" + tree.getSrcNamespace()
                + " dstNs=" + tree.getDstNamespaces());
    }
}
