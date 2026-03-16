import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class OrderService {
    private static Config cfg;

    // Flag indicating whether the service has yet seen any request.  The
    // persistence logic described in the assignment requires us to
    // inspect the very first request directed at the OrderService to
    // decide whether to retain or discard previously persisted data.
    private static boolean firstRequest = true;

    // Sentinel file name used to mark that the previous run wrote
    // persisted data which should be considered on the next startup.
    // When we persist purchases on shutdown we create this file.  On
    // startup we check for its existence and set loadedFromPersistence
    // accordingly.  The first request will then decide whether to
    // retain or wipe that persisted state.  After the decision is
    // applied the sentinel file is removed.
    private static final String PERSISTENCE_FLAG = "order_persist.flag";

    // True if the persistence flag existed when the service started.
    private static boolean loadedFromPersistence = false;

    // Map of userId -> (productId -> quantity) representing total purchases
    // Use LinkedHashMap to preserve insertion order when serializing to JSON
    // Use concurrent maps for purchases to allow updates from multiple
    // threads without explicit synchronization.  Each user id maps to a
    // per-user map of product id to quantity.  The outer map and
    // inner maps are concurrent to enable concurrent reads and
    // modifications.
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentHashMap<Integer, Integer>> purchases = new java.util.concurrent.ConcurrentHashMap<>();

    // Flag indicating that the purchases map has been modified and
    // should be persisted by the background saver.  Using volatile
    // ensures visibility across threads.
    private static volatile boolean dirty = false;

    // Scheduled executor used to periodically persist purchases to disk.
    private static java.util.concurrent.ScheduledExecutorService scheduler;

    /**
     * Endpoint record used to store an IP and port for a backend
     * instance.  The gateway reads lists of endpoints from the
     * configuration file and uses round-robin selection to load
     * balance requests among them.
     */
    private static final class Endpoint {
        final String ip;
        final int port;
        Endpoint(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    // Lists of backend instances for user and product services.  These
    // lists are populated on startup by parsing the configuration file.
    private static java.util.List<Endpoint> userServices;
    private static java.util.List<Endpoint> productServices;

    // Atomic counters used for round-robin selection of backend
    // instances.  Each call to select an endpoint increments the
    // corresponding counter and picks the next backend in the list.
    private static final java.util.concurrent.atomic.AtomicInteger userIndex = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger productIndex = new java.util.concurrent.atomic.AtomicInteger();

    // File used to persist the purchases map between service restarts.  The file
    // lives in the working directory of the compiled OrderService so that
    // `runme.sh -c` does not inadvertently erase persisted data.  On
    // startup we attempt to read this file if present and populate the
    // in-memory purchases map.  On every successful order placement we
    // update the map and write it back to disk to persist the new state.
    private static final String PURCHASES_FILENAME = "purchases.json";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java OrderService config.json");
            System.exit(2);
        }
        cfg = Config.load(args[0]);
        Config.Service me = cfg.get("OrderService");
        if (me == null) throw new RuntimeException("Missing OrderService in config.json");

        // On startup, determine if a persistence flag exists.  If the
        // flag file exists then we previously shut down and persisted
        // state; set loadedFromPersistence accordingly.  The first
        // request will decide whether to keep or discard this state.
        java.io.File flag = new java.io.File(PERSISTENCE_FLAG);
        if (flag.exists()) {
            loadedFromPersistence = true;
        }

        // attempt to load persisted purchase data on start.  Even if
        // loadedFromPersistence is false we load purchases; the map may be
        // empty if no file exists.  However, we will only consider wiping
        // this data on the first request if loadedFromPersistence is true.
        loadPurchases();
        // Start a background persistence task.  Persisting purchases on
        // every request severely limits throughput.  A single-threaded
        // scheduled executor writes the purchases to disk once per
        // second if there have been any updates.  This strikes a
        // balance between data durability and performance.
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            if (dirty) {
                // Capture and reset the dirty flag before saving to
                // avoid missing modifications that occur during the
                // save operation.
                dirty = false;
                savePurchases();
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);

        // Register a shutdown hook to persist purchases on shutdown and
        // stop the scheduler.  On JVM exit we perform one final save
        // to ensure that the most recent state is stored.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (scheduler != null) scheduler.shutdownNow();
            } catch (Exception ignored) {}
            savePurchases();
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(me.port), 0);

        // Parse lists of backend endpoints from the configuration file.
        // The OrderService itself still reads its own address from
        // Config, but we need to discover the locations of the
        // UserService and ProductService instances for direct calls.
        parseBackendEndpoints(args[0]);

        // Internal endpoint used to wipe or retain state based on the first
        // request.  This is called by OrderService itself when the
        // persistence decision is made.  Register before public routes.
        server.createContext("/__internal/wipe", OrderService::handleWipe);

        // Register more specific context before generic /user to avoid proxying our own route
        server.createContext("/user/purchased", OrderService::handleUserPurchased);

        // Proxy endpoints (public) - forward user and product requests
        // directly to the appropriate service instance using
        // round-robin load balancing.
        server.createContext("/user", OrderService::proxyToUserService);
        server.createContext("/product", OrderService::proxyToProductService);

        // Order endpoint
        server.createContext("/order", OrderService::handleOrder);

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("OrderService listening on " + me.ip + ":" + me.port);
    }

    /**
     * Proxy a request to one of the UserService instances.  This handler
     * forwards both GET and POST requests to a backend using round-robin
     * selection.  It preserves the HTTP method, path and query
     * parameters.  The request body is forwarded for POST requests.  A
     * 404 response is returned for methods other than GET/POST.  Prior
     * to forwarding we ensure the persistence decision has been made.
     */
    private static void proxyToUserService(HttpExchange ex) throws IOException {
        // Proxying a request is not a restart command; if this is the
        // first request we treat it as freshStart=true.
        ensureFirstRequestDecision(true);
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("POST")) {
            send(ex, 404, "{}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String fullPath = path + (query == null ? "" : "?" + query);
        String body = null;
        if (method.equals("POST")) {
            body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }
        Endpoint ep = selectUserEndpoint();
        String url = "http://" + ep.ip + ":" + ep.port + fullPath;
        HttpUtil.Resp r = HttpUtil.request(method, url, body);
        String out = (r.body == null || r.body.isEmpty()) ? "{}" : r.body;
        send(ex, r.code, out);
    }

    /**
     * Proxy a request to one of the ProductService instances using
     * round-robin load balancing.  This handler supports GET and POST
     * requests and behaves similarly to proxyToUserService.
     */
    private static void proxyToProductService(HttpExchange ex) throws IOException {
        ensureFirstRequestDecision(true);
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("POST")) {
            send(ex, 404, "{}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String fullPath = path + (query == null ? "" : "?" + query);
        String body = null;
        if (method.equals("POST")) {
            body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }
        Endpoint ep = selectProductEndpoint();
        String url = "http://" + ep.ip + ":" + ep.port + fullPath;
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
        if (cmd == null) { sendOrderStatus(ex, 400, "Invalid Request"); return; }

        // If this is the very first request to the OrderService we decide
        // whether to keep or discard previous data.  A command of
        // "restart" indicates we should retain data; anything else
        // indicates we should start fresh.  After this call the
        // decision flag is cleared and subsequent requests will not
        // trigger a wipe.
        if (cmd.equals("restart")) {
            ensureFirstRequestDecision(false);
            // A restart command does not place an order.  Return a
            // success status to the client.
            Map<String,Object> resp = new LinkedHashMap<>();
            resp.put("status", "Success");
            send(ex, 200, SimpleJson.stringify(resp));
            return;
        } else if (cmd.equals("shutdown")) {
            // Persist purchases and exit.  Optionally instruct other
            // services to persist as well.  For now, just exit.
            // Ensure we have made the persistence decision.  If this is
            // the first request, instruct other services to wipe their
            // data (freshStart=true) so that on next start we begin
            // fresh.  If it is not the first request this call will do
            // nothing.  We pass true because shutting down should
            // prepare for a fresh start on the next run.
            ensureFirstRequestDecision(true);
            // Persist purchases to disk
            savePurchases();
            // Create the persistence flag to mark that a prior run
            // persisted data.  This will cause loadedFromPersistence
            // to be true on next startup.
            try {
                java.io.File flagFile = new java.io.File(PERSISTENCE_FLAG);
                if (!flagFile.exists()) {
                    flagFile.createNewFile();
                }
            } catch (Exception e) {
                // ignore errors creating flag file
                System.err.println("Failed to create persistence flag: " + e);
            }
            Map<String,Object> resp = new LinkedHashMap<>();
            resp.put("status", "Success");
            send(ex, 200, SimpleJson.stringify(resp));
            // Shutdown after responding
            new Thread(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                System.exit(0);
            }).start();
            return;
        } else {
            // For any other command we should start fresh if this is the first request
            ensureFirstRequestDecision(true);
        }

        if (!cmd.equals("place order")) { sendOrderStatus(ex, 400, "Invalid Request"); return; }

        Integer productId = intOrNull(obj.get("product_id"));
        Integer userId = intOrNull(obj.get("user_id"));
        Integer quantity = intOrNull(obj.get("quantity"));

        if (productId == null || userId == null || quantity == null) { sendOrderStatus(ex, 400, "Invalid Request"); return; }
        if (quantity <= 0) { sendOrderStatus(ex, 400, "Invalid Request"); return; }

        // Check user exists via direct GET /user/<id>
        HttpUtil.Resp userResp = userGet("/user/" + userId);
        if (userResp.code != 200) {
            sendOrderStatus(ex, 404, "Invalid Request");
            return;
        }

        // Check product exists via direct GET /product/<id>
        HttpUtil.Resp prodResp = productGet("/product/" + productId);
        if (prodResp.code != 200) {
            sendOrderStatus(ex, 404, "Invalid Request");
            return;
        }

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

        HttpUtil.Resp upd = productPost("/product", SimpleJson.stringify(update));
        if (upd.code != 200) {
            sendOrderStatus(ex, 500, "Invalid Request");
            return;
        }

        // Success
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("product_id", productId);
        resp.put("user_id", userId);
        resp.put("quantity", quantity);
        resp.put("status", "Success");

        // Record the purchase for the user.  Keep a running total of
        // quantities purchased per product.  Use concurrent maps and
        // atomic operations to avoid race conditions under high
        // concurrency.  Persist asynchronously by marking the state
        // as dirty so the background saver will flush it later.
        purchases.compute(userId, (uid, userMap) -> {
            if (userMap == null) {
                userMap = new java.util.concurrent.ConcurrentHashMap<>();
            }
            userMap.merge(productId, quantity, Integer::sum);
            return userMap;
        });
        dirty = true;
        send(ex, 200, SimpleJson.stringify(resp));
    }

    /**
     * Ensure that the persistence decision has been made.  On the very
     * first request to the OrderService, this method is invoked with
     * {@code freshStart} set based on whether the request was a
     * restart command.  If {@code freshStart} is true, all services
     * purge their persisted data and start with empty databases.
     * Otherwise, previously persisted data is retained.  Subsequent
     * calls to this method are no-ops.
     *
     * @param freshStart true if the first request indicates a fresh start
     */
    private static synchronized void ensureFirstRequestDecision(boolean freshStart) {
        if (!firstRequest) return;
        firstRequest = false;
        // If we did not load from persistence (i.e. no previous run left
        // persisted state), then there is nothing to wipe.  Simply
        // delete any lingering flag and return.  Do not send wipe
        // requests to other services in this case.
        if (!loadedFromPersistence) {
            // remove the flag file if it somehow exists
            java.io.File flagFile = new java.io.File(PERSISTENCE_FLAG);
            if (flagFile.exists()) {
                flagFile.delete();
            }
            return;
        }
        // At this point loadedFromPersistence is true.  We need to
        // instruct each service whether to wipe based on freshStart.
        try {
            // Determine addresses for internal services
            Config.Service userSvc = cfg.get("UserService");
            Config.Service prodSvc = cfg.get("ProductService");
            // Body payload for wipe
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("freshStart", freshStart);
            String jsonPayload = SimpleJson.stringify(payload);
            // Send wipe to UserService
            if (userSvc != null) {
                String url = "http://" + userSvc.ip + ":" + userSvc.port + "/__internal/wipe";
                HttpUtil.request("POST", url, jsonPayload);
            }
            // Send wipe to ProductService
            if (prodSvc != null) {
                String url = "http://" + prodSvc.ip + ":" + prodSvc.port + "/__internal/wipe";
                HttpUtil.request("POST", url, jsonPayload);
            }
            // Wipe our own purchases if starting fresh
            if (freshStart) {
                purchases.clear();
                java.io.File f = new java.io.File(PURCHASES_FILENAME);
                if (f.exists()) {
                    f.delete();
                }
            }
        } catch (Exception e) {
            // ignore errors during wipe
            System.err.println("Error during fresh start decision: " + e);
        }
        // After making the decision, remove the persistence flag so
        // subsequent runs will treat the state as loaded normally.
        java.io.File flagFile = new java.io.File(PERSISTENCE_FLAG);
        if (flagFile.exists()) {
            flagFile.delete();
        }
    }

    /**
     * Handle internal wipe requests for the OrderService.  This endpoint
     * expects a POST request with a JSON body containing a boolean
     * field "freshStart".  When freshStart is true the in-memory
     * purchases map is cleared and the persisted file is deleted.
     * When false nothing is done.  A 200 status code is always
     * returned.
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
            purchases.clear();
            java.io.File f = new java.io.File(PURCHASES_FILENAME);
            if (f.exists()) {
                f.delete();
            }
        }
        send(ex, 200, "{}");
    }

    /**
     * Handle GET requests for a user's purchased products.  The endpoint
     * expects a path of the form /user/purchased/{userId}.  It returns a
     * JSON object where each key is a product id (as a string) and each
     * value is the quantity purchased by the user.  If the user does not
     * exist a 404 status code is returned.  If the user exists but has
     * not purchased anything, 200 with an empty JSON object is returned.
     */
    private static void handleUserPurchased(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equals("GET")) {
            send(ex, 404, "{}");
            return;
        }

        // For any request to this endpoint, determine if we need to wipe
        // persisted data.  Since this cannot be a restart command, we
        // always pass freshStart=true.
        ensureFirstRequestDecision(true);
        String path = ex.getRequestURI().getPath();
        // path should be /user/purchased or /user/purchased/<id>
        String[] parts = path.split("/");
        // parts[0] is "", parts[1] = "user", parts[2] = "purchased"
        if (parts.length < 3 || !"user".equals(parts[1]) || !"purchased".equals(parts[2])) {
            send(ex, 404, "{}");
            return;
        }
        if (parts.length < 4 || parts[3].isEmpty()) {
            // missing id
            send(ex, 404, "{}");
            return;
        }
        Integer userId = intOrNull(parseNumberString(parts[3]));
        // If id is not numeric, treat as non-existent user
        if (userId == null) {
            send(ex, 404, "{}");
            return;
        }

        // Verify the user exists via direct call to a user service
        HttpUtil.Resp userResp = userGet("/user/" + userId);
        if (userResp.code != 200) {
            send(ex, 404, "{}");
            return;
        }

        Map<Integer,Integer> userMap = purchases.get(userId);
        if (userMap == null || userMap.isEmpty()) {
            send(ex, 200, "{}");
            return;
        }
        // Convert to JSON-friendly map
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<Integer,Integer> e : userMap.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        send(ex, 200, SimpleJson.stringify(out));
    }

    /**
     * Parse a numeric string to a Number object.  This helper is used to
     * convert path parameters into Number types in order to reuse
     * {@code intOrNull} which accepts any {@link Number} instance.  If
     * parsing fails or the string is not a valid integer representation
     * then null is returned.
     */
    private static Number parseNumberString(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Load persisted purchase information from disk.  If the file does not
     * exist this method does nothing.  If there is an error reading or
     * parsing the file the purchases map remains empty.  This method
     * should be invoked once at startup.
     */
    private static void loadPurchases() {
        File f = new File(PURCHASES_FILENAME);
        if (!f.exists()) return;
        try {
            String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (json.isEmpty()) return;
            Map<String,Object> root = SimpleJson.parseObject(json);
            for (Map.Entry<String,Object> entry : root.entrySet()) {
                try {
                    int uid = Integer.parseInt(entry.getKey());
                    Object innerObj = entry.getValue();
                    if (!(innerObj instanceof Map<?,?>)) continue;
                    Map<?,?> innerMap = (Map<?,?>) innerObj;
                    java.util.concurrent.ConcurrentHashMap<Integer,Integer> map = new java.util.concurrent.ConcurrentHashMap<>();                    for (Map.Entry<?,?> e : innerMap.entrySet()) {
                        try {
                            int pid = Integer.parseInt(e.getKey().toString());
                            Object v = e.getValue();
                            Integer qty;
                            if (v instanceof Number) {
                                qty = ((Number) v).intValue();
                            } else {
                                qty = Integer.parseInt(v.toString());
                            }
                            map.put(pid, qty);
                        } catch (Exception ignored) {
                            // skip malformed entries
                        }
                    }
                    if (!map.isEmpty()) {
                        purchases.put(uid, map);
                    }
                } catch (Exception ignored) {
                    // skip malformed user entries
                }
            }
        } catch (Exception e) {
            // If there's an error we leave purchases empty
            System.err.println("Failed to load purchases: " + e);
        }
    }

    /**
     * Persist the current purchases map to disk.  The map is converted to
     * a JSON object with string keys so that it can be parsed by
     * {@link SimpleJson}.  Any IO or serialization errors are silently
     * ignored.
     */
    private static void savePurchases() {
        try {
            // Convert to JSON-friendly structure
            Map<String,Object> root = new LinkedHashMap<>();
            for (Map.Entry<Integer, java.util.concurrent.ConcurrentHashMap<Integer,Integer>> entry 
                : purchases.entrySet()) {
                Map<String,Object> inner = new LinkedHashMap<>();
                for (Map.Entry<Integer,Integer> e : entry.getValue().entrySet()) {
                    inner.put(String.valueOf(e.getKey()), e.getValue());
                }
                root.put(String.valueOf(entry.getKey()), inner);
            }
            String json = SimpleJson.stringify(root);
            java.nio.file.Files.write(new File(PURCHASES_FILENAME).toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // silently ignore persistence errors
            System.err.println("Failed to save purchases: " + e);
        }
    }

    private static HttpUtil.Resp iscsGet(String path) throws IOException {
        throw new UnsupportedOperationException("This method should not be used in Component 2");
    }

    private static HttpUtil.Resp iscsPost(String path, String json) throws IOException {
        throw new UnsupportedOperationException("This method should not be used in Component 2");
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

    /**
     * Parse backend service definitions from the configuration file.  The
     * configuration may define a single service as an object with "ip"
     * and "port" fields or multiple instances as a JSON array of such
     * objects.  For example:
     *
     *     "UserService": [ {"ip":"10.0.0.1","port":14001}, {"ip":"10.0.0.2","port":14001} ],
     *     "ProductService": { "ip":"10.0.0.3", "port":15000 }
     *
     * This method populates the lists userServices and productServices.
     * If parsing fails or the entries are missing the OrderService will
     * fall back to the single address from the Config object.
     *
     * @param configPath path to the configuration JSON file
     */
    private static void parseBackendEndpoints(String configPath) {
        try {
            String json = java.nio.file.Files.readString(java.nio.file.Paths.get(configPath), java.nio.charset.StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            java.util.Map<String,Object> root = (java.util.Map<String,Object>) SimpleJson.parseObject(json);
            userServices = parseEndpoints(root.get("UserService"));
            productServices = parseEndpoints(root.get("ProductService"));
        } catch (Exception e) {
            // ignore parse errors
        }
        // Fallback if lists are empty: use Config.get which returns a single service
        if (userServices == null || userServices.isEmpty()) {
            userServices = new java.util.ArrayList<>();
            Config.Service us = cfg.get("UserService");
            if (us != null) userServices.add(new Endpoint(us.ip, us.port));
        }
        if (productServices == null || productServices.isEmpty()) {
            productServices = new java.util.ArrayList<>();
            Config.Service ps = cfg.get("ProductService");
            if (ps != null) productServices.add(new Endpoint(ps.ip, ps.port));
        }
    }

    /**
     * Helper to parse a configuration entry into a list of Endpoint
     * objects.  The entry may be null, a single map of ip/port, or
     * an array of maps.  Invalid entries are ignored.
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<Endpoint> parseEndpoints(Object val) {
        java.util.List<Endpoint> list = new java.util.ArrayList<>();
        if (val == null) return list;
        try {
            if (val instanceof java.util.List<?> l) {
                for (Object item : l) {
                    if (item instanceof java.util.Map<?,?> map) {
                        Object ipObj = map.get("ip");
                        Object portObj = map.get("port");
                        if (ipObj instanceof String && portObj instanceof Number) {
                            list.add(new Endpoint((String) ipObj, ((Number) portObj).intValue()));
                        }
                    }
                }
            } else if (val instanceof java.util.Map<?,?> map) {
                Object ipObj = map.get("ip");
                Object portObj = map.get("port");
                if (ipObj instanceof String && portObj instanceof Number) {
                    list.add(new Endpoint((String) ipObj, ((Number) portObj).intValue()));
                }
            }
        } catch (Exception ignored) {
            // ignore malformed entries
        }
        return list;
    }

    /**
     * Select a UserService endpoint using round-robin.  If the list is
     * empty this method throws IllegalStateException.  The index
     * increments atomically and wraps around using modulo.
     */
    private static Endpoint selectUserEndpoint() {
        if (userServices == null || userServices.isEmpty()) {
            throw new IllegalStateException("No user service endpoints configured");
        }
        int idx = userIndex.getAndIncrement();
        int index = Math.floorMod(idx, userServices.size());
        return userServices.get(index);
    }

    /**
     * Select a ProductService endpoint using round-robin.  See
     * selectUserEndpoint for details.
     */
    private static Endpoint selectProductEndpoint() {
        if (productServices == null || productServices.isEmpty()) {
            throw new IllegalStateException("No product service endpoints configured");
        }
        int idx = productIndex.getAndIncrement();
        int index = Math.floorMod(idx, productServices.size());
        return productServices.get(index);
    }

    /**
     * Perform a GET against a UserService backend.  The path must begin
     * with a slash (e.g. "/user/123").  This helper selects a
     * backend and constructs the full URL.  It returns the raw
     * HttpUtil.Resp from the request.
     */
    private static HttpUtil.Resp userGet(String path) throws IOException {
        Endpoint ep = selectUserEndpoint();
        String url = "http://" + ep.ip + ":" + ep.port + path;
        return HttpUtil.request("GET", url, null);
    }

    /**
     * Perform a POST against a UserService backend.  The path must
     * begin with a slash.  The JSON body is forwarded as-is.
     */
    private static HttpUtil.Resp userPost(String path, String json) throws IOException {
        Endpoint ep = selectUserEndpoint();
        String url = "http://" + ep.ip + ":" + ep.port + path;
        return HttpUtil.request("POST", url, json);
    }

    /**
     * Perform a GET against a ProductService backend.
     */
    private static HttpUtil.Resp productGet(String path) throws IOException {
        Endpoint ep = selectProductEndpoint();
        String url = "http://" + ep.ip + ":" + ep.port + path;
        return HttpUtil.request("GET", url, null);
    }

    /**
     * Perform a POST against a ProductService backend.
     */
    private static HttpUtil.Resp productPost(String path, String json) throws IOException {
        Endpoint ep = selectProductEndpoint();
        String url = "http://" + ep.ip + ":" + ep.port + path;
        return HttpUtil.request("POST", url, json);
    }
}
