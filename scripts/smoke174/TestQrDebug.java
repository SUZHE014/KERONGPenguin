import cn.kerong.bot.tools.QrCode;

public class TestQrDebug {
    public static void main(String[] args) {
        String[] texts = {"HELLO", "12", "https://kerong.xin/penguin/tutorial.html",
                "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMN"};
        for (String text : texts) {
            QrCode.DebugInfo info = QrCode.encodeDebug(text);
            StringBuilder sb = new StringBuilder();
            for (int b : info.getCodewords()) sb.append(String.format("%02x ", b));
            System.out.println("TEXT=" + text);
            System.out.println("VER=" + info.getVersion() + " MASK=" + info.getMask()
                    + " FMT=" + Integer.toBinaryString(info.getFormatBits()));
            System.out.println("CODEWORDS=" + sb.toString().trim());
        }
    }
}
