#!/usr/bin/env bash
set -euo pipefail

cat > src/ProductService/ProductService.java <<'JAVA'
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ProductService {
    private static final Map<Integer, Product> products = new HashMap<>();

    private static final class Product {
        int id;
        String name;
        String description;
        double price;
        int quantity;
        Product(int id, String n, String d, double p, int q){
            this.id=id; this.name=n; this.description=d; this.price=p; this.quantity=q;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { System.err.println("Usage: java ProductService config.json"); System.exit(2); }
        Config cfg = Config.load(args[0]);
        Config.Service me = cfg.get("ProductService");
        i        i        i        i        i        i        i   tService in config.json");

        HttpServer server = HttpServer.create(new InetSocketAddress(me.port), 0);
        server.createContext("/product", ProductService::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("ProductService listening on " + me.ip + ":" + me.port);
    }

    private static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if (method.equals("GET")) {
            String[] parts = path.split("/");
            if (parts.length != 3) { send(ex, 400, "{}"); return; }
            Integer id = parseInt(parts[2]);
            if (id == null) { send(ex, 400, "{}"); return; }

            Product p = products.get(id);
            if (p == null) { send(ex, 404, "{}"); return; }
            send(ex, 200, SimpleJson.stringify(toMap(p)));
            return;
        }

        if (!method.equals("POST")) { send(ex, 404, "{}"); return; }
        if (!path.equals("/product")) { send(ex, 404, "{}"); return; }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String,Object> obj;
        try { obj = SimpleJson.parseObject(body); }
        catch (Exception e) { send(ex, 400, "{}"); return; }

        String cmd = asString(obj.get("command"));
        if (cmd == null || cmd.isEmpty()) { send(ex, 400, "{}"); return; }

        switch (cmd) {
            case "create" -> handleCreate(ex, obj);
            case "update" -> handleUpdate(ex, obj);
            case "delete" -> handleDelete(ex, obj);
            default -> send(ex, 400, "{}");
        }
    }

    private static void handleCreate(HttpExchange ex, Map<String,Object> obj) throws IOException {
        Integer id = intOrNull(obj.get("id"));
        String name = asString(obj.get("name"));
        String description = asString(obj.get("description"));
        Double price = asDouble(obj.get("price"));
        Integer quantity = intOrNull(obj.get("quantity"));

        if (id == null || name == null || description == null || price == null || quantity == null) { send(ex, 400, "{}"); return; }
        if (name.isEmpty() || description.isEmpty()) { send(ex, 400, "{}"); return; }
        if (price < 0 || quantity < 0) { send(ex, 400, "{}"); return; }
        if (products.containsKey(id)) { send(ex, 409, "{}"); return; }

        Product p = new Product(id, name, description, price, quantity);
        products.put(id, p);
        send(ex, 200, SimpleJson.stringify(toMap(p)));
    }

    private static void handleUpdate(HttpExchange ex, Map<String,Object> obj) throws IOException {
        Integer id = intOrNull(obj.get("id"));
        if (id == null) { send(ex, 400, "{}"); return; }

        Product p = products.get(id);
        if (p == null) { send(ex, 404, "{}"); return; }

        boolean any = false;

        if (obj.containsKey("name")) {
            String name = asString(obj.get("name"));
            if (name == null || name.isEmpty()) { send(ex, 400, "{}"); return; }
            p.name = name; any = true;
        }
        if (obj.containsKey("description")) {
            String d = asString(obj.get("description"));
            if (d == null || d.isEmpty()) { send(ex, 400, "{}"); return; }
            p.description = d; any = true;
        }
        if (obj.containsKey("price")) {
            Double price = asDouble(obj.get("price"));
            if (price == null || price < 0) { send(ex, 400, "{}"); return; }
            p.price = price; any = true;
        }
        if (obj.containsKey("quantity")) {
            Integer q = intOrNull(obj.get("quantity"));
            if (q == null || q < 0) { send(ex, 400, "{}"); return; }
            p.quantity = q; any = true;
        }

        if (!any) { send(ex, 400, "{}"); return; }
        send(ex, 200, SimpleJson.stringify(toMap(p)));
    }

    private static void handleDelete(HttpExchange ex, Map<String,Object> obj) throws IOException {
        Integer id = intOrNull(obj.get("id"));
        String name = asString(obj.get("name"));
        Double price = asDouble(obj.get("price"));
        Integer quantity = intOrNull(obj.get("quantity"));

        if (id == null || name == null || price == null || quantity == null) { send(ex, 400, "{}"); return; }

        Product p = products.get(id);
        if (p == null) { send(ex, 404, "{}"); return; }

        if (!p.name.equals(name) || p.price != price.doubleValue() || p.quantity != quantity.intValue()) {
            send(ex, 401, "{}"); return;
        }

        products.remove(id);
        send(ex, 200, "{}");
    }

    private static Map<String,Object> toMap(Product p) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("name", p.name);
        m.put("description", p.description);
        m.put("price", p.price);
        m.put("quantity", p.quantity);
        return m;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static Integer parseInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return null; } }
    private static String asString(Object o) { return (o instanceof String) ? (String)o : null; }
    private static Double asDouble(Object o) { return (o instanceof Number) ? ((Number)o).doubleValue() : null; }

    // Accept integer JSON numbers even if they arrived as Double like 3.0. Reject 3.5.
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

echo "Step 4 written."
