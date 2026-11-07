import cn.kerong.bot.tools.QrCode;

import java.io.PrintWriter;

/**
 * 1.7.4 QR 编码器冒烟：多组文本编码 → 输出矩阵（# 行 / . 列），
 * 由外部脚本用 qrcode 库参考矩阵比对 + OpenCV 解码双重验证。
 */
public class TestQr174 {
    public static void main(String[] args) throws Exception {
        String[] texts = {
                "https://kerong.xin/penguin/tutorial.html",
                "HELLO",
                "https://kerong.xin/penguin/",
                "12",
                "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMN",
                "中文内容测试-二维码"
        };
        if (args.length > 0) {
            // args[0] = 矩阵 dump 输出文件；args[1]（可选）= ASCII 演示输出文件
            try (PrintWriter out = new PrintWriter(args[0], "UTF-8")) {
                for (String text : texts) {
                    QrCode.Matrix matrix = QrCode.encode(text);
                    out.println("TEXT=" + text);
                    out.println("SIZE=" + matrix.getSize());
                    StringBuilder sb = new StringBuilder();
                    for (int r = 0; r < matrix.getSize(); r++) {
                        for (int c = 0; c < matrix.getSize(); c++) {
                            sb.append(matrix.getModules()[r][c] ? '#' : '.');
                        }
                        sb.append('\n');
                    }
                    out.print(sb);
                    out.println("END");
                }
            }
            System.out.println("matrix dump written to " + args[0]);
            if (args.length > 1) {
                try (PrintWriter out = new PrintWriter(args[1], "UTF-8")) {
                    String url = texts[0];
                    out.println("encode OK, size=" + QrCode.encode(url).getSize());
                    out.println(QrCode.encodeAscii(url, 2));
                }
                System.out.println("ascii demo written to " + args[1]);
            }
            return;
        }
        // 控制台演示 + 边界测试
        String url = texts[0];
        QrCode.Matrix m = QrCode.encode(url);
        System.out.println("encode OK, size=" + m.getSize());
        System.out.println(QrCode.encodeAscii(url, 2));
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) sb.append("0123456789");
            QrCode.encode(sb.toString());
            System.out.println("OVERFLOW-TEST: FAIL (no exception)");
        } catch (IllegalArgumentException expected) {
            System.out.println("OVERFLOW-TEST: PASS (" + expected.getMessage() + ")");
        }
        try {
            QrCode.encode("");
            System.out.println("EMPTY-TEST: FAIL");
        } catch (IllegalArgumentException expected) {
            System.out.println("EMPTY-TEST: PASS");
        }
    }
}
