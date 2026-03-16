import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class HttpUtil {
    public static final class Resp {
        public final int code;
        public final String body;
        public Resp(int code, String body){ this.code = code; this.body = body; }
    }

    public static Resp request(String method, String url, String bodyJson) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Content-Type", "application/json");
        if (bodyJson != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String respBody = "";
        if (is != null) respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        return new Resp(code, respBody);
    }
}
