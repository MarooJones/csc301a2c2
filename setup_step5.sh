#!/usr/bin/env bash
set -euo pipefail

cat > src/ISCS/ISCS.java <<'JAVA'
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
JAVA

cat > src/OrderService/OrderService.java <<'JAVA'
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class OrderService {
    private static Config cfg;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { System.err.println("Usage: java OrderService config.json"); System.exit(2); }
        cfg = Config.load(args[0]);
        Config.Service me = cfg.get("OrderService");
        if (me == null) throw new RuntimeException("Missing OrderService in config.json");

        HttpServer server = HttpServer.create(new InetSocketAddress(me.port), 0);

        // Proxy endpoints (public) - these go to ISCS
        server.createContext("/user", OrderService::proxyToISCS);
        server.createContext("/product", OrderService::proxyToISCS);

        // Order endpoint
        server.createContext("/order", OrderService::handleOrder);

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("OrderService listening on " + me.ip + ":" + me.port);
    }

    private static void proxyToISCS(HttpExchange ex) throws IOException {
        Config.Service iscs = cfg.get("InterServiceCommunication");
        if (iscs == null) { send(ex, 500, "{}"); return; }

        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String fullPath = path + (query == null ? "" : "?" + query);

        String url = "http://" + iscs.ip + ":" + iscs.port + fullPath;
        String body = null;
        if (method.equals("POST")) body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        HttpUtil.Resp r = HttpUtil.request(method, url, body);
        String out = (r.body == null || r.body.isEmpty()) ? "{}" : r.body;
        send(ex, r.code, out);
    }

    private static void handleOrder(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 404, "{}"); return; }
        if (!ex.getRequestURI().getPath().equals("/order")) { send(ex, 404, "{}"); return; }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String,Object> obj;
        try { obj = SimpleJson.parseObject(body); }
        catch (Exception e) { sendOrderStatus(ex, 400, "Invalid Request"); return; }

        String cmd = asString(obj.get("command"));
        if (cmd == null || !cmd.equals("place order")) { sendOrderStatus(ex, 400, "Invalid Request"); return; }

        Integer productId = intOrNull(obj.get("product_id"));
        Integer userId = intOrNull(obj.get("user_id"));
        Integer quantity = intOrNull(obj.get("quantity"));

        if (productId == null || userId == null || quantity == null) { sendOrderStatus(ex, 400, "Invalid Request"); return; }
        if (quantity <= 0) { sendOrderStatus(ex, 400, "Invalid Request"); return; }

        // Check user exists via ISCS GET /user/<id>
        HttpUtil.Resp userResp = iscsGet("/user/" + userId);
        if (userResp.code != 200) { sendOrderStatus(ex, 404, "Invalid Request"); return; }

        // Check product exists via ISCS GET /product/<id>
        HttpUtil.Resp prodResp = iscsGet("/product/" + productId);
        if (prodResp.code != 200) { sendOrderStatus(ex, 404, "Invalid Request"); return; }

        Map<String,Object> prodObj;
        try { prodObj = SimpleJson.parseObject(prodResp.body); }
        catch (Exception e) { sendOrderStatus(ex, 500, "Invalid Request"); return; }

        Integer stock = intOrNull(prodObj.get("quantity"));
        if (stock == null) { sendOrderStatus(ex, 500, "Invalid Request"); return; }

        if (quantity > stock) {
            sendOrderStatus(ex, 409, "Exceeded quantity limit");
            return;
        }

        // Reduce stock: POST /product {"command":"update","id":productId,"quantity":stock-quantity}
        int newQty = stock - quantity;
        Map<String,Object> update = new LinkedHashMap<>();
        update.put("command", "update");
        update.put("id", productId);
        update.put("quantity", newQty);

        HttpUtil.Resp upd = iscsPost("/product", SimpleJson.stringify(update));
        if (upd.code != 200) { sendOrderStatus(ex, 500, "Invalid Request"); return; }

        // Success
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("product_id", productId);
        resp.put("user_id", userId);
        resp.put("quantity", quantity);
        resp.put("status", "Success");
        send(ex, 200, SimpleJson.stringify(resp));
    }

    private static HttpUtil.Resp iscsGet(String path) throws IOException {
        Config.Service iscs = cfg.get("InterServiceCommunication");
        String url = "http://" + iscs.ip + ":" + iscs.port + path;
        return HttpUtil.request("GET", url, null);
    }

    private static HttpUtil.Resp iscsPost(String path, String json) throws IOException {
        Config.Service iscs = cfg.get("InterServiceCommunication");
        String url = "http://" + iscs.ip + ":" + iscs.port + path;
        return HttpUtil.request("POST", url, json);
    }

    private static void sendOrderStatus(HttpExchange ex, int code, String status) throws IOException {
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("status", status);
        send(ex, code, SimpleJson.stringify(resp));
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String asString(Object o) { return (o instanceof String) ? (String)o : null; }

    // Accept integers even if they arrived as Double like 3.0. Reject 3.5.
    private static Integer intOrNull(Object o) {
        if (!(o instanceof Number)) return null;
        double d = ((Number)o).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        if (Math.rint(d) != d) return null;
        long L = (long) d;
        if (L < Integer.MIN_VALUE || L > Integer.MAX_VALUE) return null;
        return (int) L;
    }
}
JAVA

echo "Step 5 written."
