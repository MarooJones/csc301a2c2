import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ProductService {
    // Use a thread-safe map for concurrent access by multiple HTTP handler
    // threads.  ConcurrentHashMap provides better scalability under
    // contention compared to HashMap.
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Product> products = new java.util.concurrent.ConcurrentHashMap<>();

    // Flag indicating that products have changed and should be persisted by
    // the background saver.
    private static volatile boolean dirty = false;

    // Scheduled executor to periodically persist product data to disk.  The
    // executor is started in main() and shut down via a shutdown hook.
    private static java.util.concurrent.ScheduledExecutorService scheduler;

    // File name used to persist product data between service restarts.  The
    // file will be stored in the working directory of the compiled
    // ProductService.  Each entry in the JSON object uses the product id
    // as the key and stores an object containing name, description,
    // price and quantity.
    private static final String PRODUCTS_FILENAME = "products.json";

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
        if (args.length != 1) {
            System.err.println("Usage: java ProductService config.json");
            System.exit(2);
        }
        Config cfg = Config.load(args[0]);
        Config.Service me = cfg.get("ProductService");
        if (me == null) throw new RuntimeException("Missing ProductService in config.json");

        // Attempt to load persisted product data on startup
        loadProducts();
        // Start a background persistence task to periodically flush
        // changes to disk.  Persisting on every request would slow
        // down throughput under high load, so we accumulate changes and
        // write them once per second if there have been any updates.
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            if (dirty) {
                dirty = false;
                saveProducts();
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);

        // Register a shutdown hook to persist products on clean shutdown
        // and stop the scheduler.  On JVM exit we perform one final
        // save to ensure data integrity.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (scheduler != null) {
                    scheduler.shutdownNow();
                }
            } catch (Exception ignored) {}
            saveProducts();
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(me.port), 0);
        // Internal endpoint used by OrderService to reset or retain state
        server.createContext("/__internal/wipe", ProductService::handleWipe);
        server.createContext("/product", ProductService::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("ProductService listening on " + me.ip + ":" + me.port);
        new java.util.concurrent.CountDownLatch(1).await();
    }

    /**
     * Handle internal wipe requests.  This endpoint is used by the
     * OrderService to instruct the ProductService whether to start
     * fresh or retain persisted data.  It expects a POST request with
     * a JSON body containing a boolean field "freshStart".  When
     * freshStart is true the in-memory product map is cleared and the
     * persisted file is deleted.  When false the current contents are
     * kept.  A 200 status code is always returned.
     */
    private static void handleWipe(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equals("POST")) {
            send(ex, 404, "{}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean freshStart = true;
        try {
            Map<String,Object> obj = SimpleJson.parseObject(body);
            Object fs = obj.get("freshStart");
            if (fs instanceof Boolean) freshStart = (Boolean) fs;
        } catch (Exception ignored) {
            // default to true
        }
        if (freshStart) {
            products.clear();
            java.io.File f = new java.io.File(PRODUCTS_FILENAME);
            if (f.exists()) {
                f.delete();
            }
        }
        send(ex, 200, "{}");
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
        // Mark dirty so the background saver will persist
        dirty = true;
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
        // Mark dirty so the background saver will persist
        dirty = true;
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
            send(ex, 404, "{}"); return;
        }

        products.remove(id);
        // Mark dirty so the background saver will persist
        dirty = true;
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

    // Accept integer numbers even if they arrived as 3.0 (Double). Reject 3.5.
    private static Integer intOrNull(Object o) {
        if (!(o instanceof Number)) return null;
        double d = ((Number)o).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        if (Math.rint(d) != d) return null;
        long L = (long) d;
        if (L < Integer.MIN_VALUE || L > Integer.MAX_VALUE) return null;
        return (int) L;
    }

    /**
     * Load persisted products from disk into the in-memory map.  If the file
     * does not exist or cannot be parsed the map is left empty.
     */
    private static void loadProducts() {
        java.io.File f = new java.io.File(PRODUCTS_FILENAME);
        if (!f.exists()) return;
        try {
            String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (json.isEmpty()) return;
            Map<String,Object> root = SimpleJson.parseObject(json);
            for (Map.Entry<String,Object> entry : root.entrySet()) {
                try {
                    int pid = Integer.parseInt(entry.getKey());
                    Object val = entry.getValue();
                    if (!(val instanceof Map<?,?>)) continue;
                    Map<?,?> pMap = (Map<?,?>) val;
                    String name = null;
                    String description = null;
                    Double price = null;
                    Integer quantity = null;
                    Object n = pMap.get("name");
                    if (n instanceof String) name = (String) n;
                    Object dObj = pMap.get("description");
                    if (dObj instanceof String) description = (String) dObj;
                    Object pr = pMap.get("price");
                    if (pr instanceof Number) price = ((Number) pr).doubleValue();
                    else if (pr instanceof String) price = Double.parseDouble((String) pr);
                    Object qObj = pMap.get("quantity");
                    if (qObj instanceof Number) quantity = ((Number) qObj).intValue();
                    else if (qObj instanceof String) quantity = Integer.parseInt((String) qObj);
                    if (name != null && description != null && price != null && quantity != null) {
                        products.put(pid, new Product(pid, name, description, price, quantity));
                    }
                } catch (Exception ignored) {
                    // skip malformed entries
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load products: " + e);
        }
    }

    /**
     * Persist the current product map to disk.  The products are stored in
     * a JSON object keyed by their id.  Errors during writing are silently
     * ignored.
     */
    private static void saveProducts() {
        try {
            Map<String,Object> root = new LinkedHashMap<>();
            for (Map.Entry<Integer,Product> entry : products.entrySet()) {
                Product p = entry.getValue();
                Map<String,Object> obj = new LinkedHashMap<>();
                obj.put("id", p.id);
                obj.put("name", p.name);
                obj.put("description", p.description);
                obj.put("price", p.price);
                obj.put("quantity", p.quantity);
                root.put(String.valueOf(entry.getKey()), obj);
            }
            String json = SimpleJson.stringify(root);
            java.nio.file.Files.write(new java.io.File(PRODUCTS_FILENAME).toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("Failed to save products: " + e);
        }
    }
}
