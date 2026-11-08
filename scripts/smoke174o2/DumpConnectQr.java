import cn.kerong.bot.tools.QrCode;
import java.io.PrintWriter;
import java.io.FileOutputStream;

public class DumpConnectQr {
    public static void main(String[] args) throws Exception {
        String url = "https://q.qq.com/qqbot/openclaw/connect.html?task_id="
                + "8f14e45fceea167a5a36dedd4bea2543f1b2c3d4e5" + "&source=openclaw&_wv=2";
        QrCode.Matrix matrix = QrCode.encode(url);
        try (PrintWriter out = new PrintWriter(new FileOutputStream("/tmp/qr_connect_dump.txt"), true)) {
            out.println("TEXT=" + url);
            out.println("SIZE=" + matrix.getSize());
            for (int r = 0; r < matrix.getSize(); r++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < matrix.getSize(); c++) {
                    sb.append(matrix.getModules()[r][c] ? '#' : '.');
                }
                out.println(sb.toString());
            }
            out.println("END");
        }
        System.out.println("dumped size=" + matrix.getSize() + " url_len=" + url.length());
    }
}
