import cn.kerong.bot.tools.QrCode;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Base64;

public class TestQrLoginSmoke {
    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;

        // 1. AES-256-GCM 解密往返（HuHoBot 协议：密文 = 12B IV + body + 16B tag，Base64）
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        String keyB64 = Base64.getEncoder().encodeToString(key);
        String secret = "wJalrXUtnFEMI-K7MDENG-bPxRfiCYEXAMPLEKEY";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ctAndTag = cipher.doFinal(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] combined = new byte[12 + ctAndTag.length];
        System.arraycopy(iv, 0, combined, 0, 12);
        System.arraycopy(ctAndTag, 0, combined, 12, ctAndTag.length);
        String encryptB64 = Base64.getEncoder().encodeToString(combined);

        Class<?> managerCls = Class.forName("cn.kerong.bot.QrLoginManager");
        Object manager = managerCls.getDeclaredField("INSTANCE").get(null);
        Method decrypt = managerCls.getDeclaredMethod("decryptSecret", String.class, String.class);
        decrypt.setAccessible(true);
        String decrypted = (String) decrypt.invoke(manager, encryptB64, keyB64);
        if (secret.equals(decrypted)) { pass++; System.out.println("[PASS] AES-256-GCM 解密往返"); }
        else { fail++; System.out.println("[FAIL] AES-256-GCM 解密往返: " + decrypted); }

        // 篡改密文 → 解密必须失败（GCM 认证）
        byte[] tampered = combined.clone();
        tampered[tampered.length - 1] ^= 0x01;
        String tamperedB64 = Base64.getEncoder().encodeToString(tampered);
        try {
            decrypt.invoke(manager, tamperedB64, keyB64);
            fail++; System.out.println("[FAIL] 篡改密文未被 GCM 认证拒绝");
        } catch (Exception expected) {
            pass++; System.out.println("[PASS] 篡改密文被 GCM 认证拒绝");
        }

        // 2. buildConnectUrl 拼装（反射）
        Method buildUrl = managerCls.getDeclaredMethod("buildConnectUrl", String.class);
        buildUrl.setAccessible(true);
        String url = (String) buildUrl.invoke(manager, "task+id 123/456");
        System.out.println("URL = " + url);
        if (url.startsWith("https://q.qq.com/qqbot/openclaw/connect.html?task_id=")
                && url.endsWith("&source=openclaw&_wv=2")
                && !url.contains("+") && !url.contains(" ")) {
            pass++; System.out.println("[PASS] connect URL 拼装与 URL 编码（+ 号转 %20）");
        } else { fail++; System.out.println("[FAIL] connect URL 拼装异常"); }

        // 3. QrCode.encodeAscii 对 connect URL 长度的编码 + 输出形态
        String realUrl = "https://q.qq.com/qqbot/openclaw/connect.html?task_id="
                + "8f14e45fceea167a5a36dedd4bea2543f1b2c3d4e5" + "&source=openclaw&_wv=2";
        try {
            String ascii = QrCode.encodeAscii(realUrl, 2);
            boolean blockChars = ascii.contains("\u2580") || ascii.contains("\u2584") || ascii.contains("\u2588");
            String[] lines = ascii.split("\n");
            boolean squareish = Math.abs(lines.length * 2 - lines[0].length()) <= 6;
            if (blockChars && squareish) {
                pass++; System.out.println("[PASS] connect URL ASCII 二维码可编码（" + realUrl.length() + " 字符，" + lines.length + " 行）");
            } else { fail++; System.out.println("[FAIL] ASCII 二维码形态异常"); }
        } catch (Exception e) {
            fail++; System.out.println("[FAIL] ASCII 二维码编码失败: " + e.getMessage());
        }

        // 4. 键盘构建（Keyboards.build 行提交）
        try {
            Class<?> kb = Class.forName("cn.kerong.bot.tools.Keyboards");
            Class<?> btnCls = Class.forName("cn.kerong.bot.tools.Keyboards$Button");
            java.util.List<Object> buttons = new java.util.ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                buttons.add(btnCls.getConstructor(String.class, String.class, String.class, int.class)
                        .newInstance("玩家" + i, "已选择 玩家" + i, "krg:psel:Player" + i, 1));
            }
            Method build = kb.getDeclaredMethod("build", java.util.List.class);
            build.setAccessible(true);
            Object keyboardsInstance = kb.getDeclaredField("INSTANCE").get(null);
            Object keyboard = build.invoke(keyboardsInstance, buttons);
            Object content = keyboard.getClass().getMethod("getContent").invoke(keyboard);
            String rows = content.toString();
            int labelCount = rows.split("\"label\":", -1).length - 1;
            if (labelCount == 7) {
                pass++; System.out.println("[PASS] Keyboards 7 按钮全部入键盘（行提交无遗漏）");
            } else { fail++; System.out.println("[FAIL] Keyboards 按钮数异常: " + labelCount); }
        } catch (Exception e) {
            fail++; System.out.println("[FAIL] Keyboards 构建异常: " + e);
        }

        System.out.println("结果：PASS " + pass + " / FAIL " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
