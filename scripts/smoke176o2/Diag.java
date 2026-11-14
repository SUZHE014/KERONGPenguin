import java.lang.reflect.*;
public class Diag {
    public static void main(String[] a) throws Exception {
        Class<?> cls = Class.forName("cn.kerong.penguin.spigot.stats.StatsFileService");
        Object svc = cls.getField("INSTANCE").get(null);
        Method m = cls.getDeclaredMethod("statsDirectory");
        m.setAccessible(true);
        System.out.println("statsDirectory = " + m.invoke(svc));
        Method m2 = cls.getDeclaredMethod("serverRoot");
        m2.setAccessible(true);
        System.out.println("serverRoot = " + m2.invoke(svc));
        System.out.println("CWD = " + new java.io.File(".").getAbsolutePath());
    }
}
