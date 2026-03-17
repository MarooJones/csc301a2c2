import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class ISCS {
    private static Config cfg;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { System.err.println("Usage: java ISCS config.json"); System.exit(2); }
        cfg = Config.load(args[0]);
        Config.Service me = cfg.get("InterServiceCommunication");
        if (me == null) throw new RuntimeException("Missing InterServiceCommunication in config.json");

        HttpServer server = HttpServer.create(new InetSocketAddress(me.port), 0);
        server.createContext("/user", ex -> proxy(ex, cfg.get("UserService")));
        server.createContext("/product", ex -> proxy(ex, cfg.get("ProductService")));
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("ISCS listening on " + me.ip + ":" + me.port);
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private static void proxy(HttpExchange ex, Config.Service target) throws IOException {
        if (target == null) { send(ex, 500, "{}"); return; }

        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath(); // includes /user or /product prefix and possibly /id
        String query = ex.getRequestURI().getRawQuery();
        String fullPath = path + (query == null ? "" : "?" + query);

        String url = "http://" + target.ip + ":" + target.port + fullPath;

        String body = null;
        if (method.equals("POST")) {
            body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        HttpUtil.Resp r = HttpUtil.request(method, url, body);
        String out = (r.body == null || r.body.isEmpty()) ? "{}" : r.body;
        send(ex, r.code, out);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
