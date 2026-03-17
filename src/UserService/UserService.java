import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class UserService {
    // Use a thread-safe map to allow concurrent access from multiple HTTP
    // handler threads.  ConcurrentHashMap provides better scalability
    // compared to HashMap when accessed by many threads concurrently.
    private static final java.util.concurrent.ConcurrentHashMap<Integer, User> users = new java.util.concurrent.ConcurrentHashMap<>();

    // A flag indicating whether the in-memory state has changed since the
    // last persistence.  When dirty is true the scheduled background
    // persistence thread will write the current state to disk.  Using
    // a volatile boolean ensures visibility across threads without
    // requiring synchronization.
    private static volatile boolean dirty = false;

    // A scheduled executor used to periodically persist the user map to
    // disk.  Persisting on every request would drastically reduce
    // throughput under high load.  Instead, we accumulate changes and
    // flush them to disk once per second.  The executor is started in
    // main() and shutdown via a shutdown hook.
    private static java.util.concurrent.ScheduledExecutorService scheduler;

    // File name used to persist user data between service restarts.  The
    // file will be stored in the working directory of the compiled
    // UserService.  Each entry in the JSON object uses the user id as
    // the key and stores an object containing username, email and
    // passwordHash.
    private static final String USERS_FILENAME = "users.json";

    private static final class User {
        int id;
        String username;
        String email;
        String passwordHash;
        User(int id, String u, String e, String ph){ this.id=id; this.username=u; this.email=e; this.passwordHash=ph; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java UserService config.json");
            System.exit(2);
        }
        Config cfg = Config.load(args[0]);
        Config.Service me = cfg.get("UserService");
        if (me == null) throw new RuntimeException("Missing UserService in config.json");

        // Attempt to load persisted users on startup
        loadUsers();
        // Start a background persistence task.  This thread wakes up
        // periodically and writes the current state to disk if it has
        // changed.  Without this periodic persistence the server would
        // either need to persist on every request (hurting throughput) or
        // risk losing data between manual persistence operations.  A
        // single-threaded executor is sufficient because only one
        // persistence operation should run at a time.
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            // Check if there have been modifications since the last
            // flush.  Using a local snapshot of the flag and setting
            // dirty=false before saving avoids missing writes that
            // occur concurrently with the save operation.
            if (dirty) {
                dirty = false;
                saveUsers();
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);

        // Register a shutdown hook to persist the user database on clean
        // shutdown and stop the scheduler.  When the JVM exits the
        // persistence thread will be terminated and we perform one
        // final save to ensure that the most recent state is stored on
        // disk.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (scheduler != null) {
                    scheduler.shutdownNow();
                }
            } catch (Exception ignored) {}
            saveUsers();
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(me.port), 0);
        // Internal endpoint used by OrderService to reset or retain state based
        // on the first request in a workload.  This should be registered
        // before /user so it does not get proxied by other contexts.
        server.createContext("/__internal/wipe", UserService::handleWipe);
        server.createContext("/user", UserService::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("UserService listening on " + me.ip + ":" + me.port);
        new java.util.concurrent.CountDownLatch(1).await();
    }

    /**
     * Handle internal wipe requests.  This endpoint is used by the
     * OrderService to instruct the UserService whether to start fresh or
     * retain persisted data.  It expects a POST request with a JSON
     * body containing a boolean field "freshStart".  When freshStart
     * is true the in-memory user map is cleared and the persisted file
     * is deleted.  When false the current contents are kept.  A 200
     * status code is always returned.
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
            if (fs instanceof Boolean) {
                freshStart = (Boolean) fs;
            }
        } catch (Exception ignored) {
            // default to true if parsing fails
        }
        if (freshStart) {
            users.clear();
            java.io.File f = new java.io.File(USERS_FILENAME);
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

            User u = users.get(id);
            if (u == null) { send(ex, 404, "{}"); return; }

            send(ex, 200, SimpleJson.stringify(toMap(u, true))); // include password
            return;
        }

        if (!method.equals("POST")) { send(ex, 404, "{}"); return; }
        if (!path.equals("/user")) { send(ex, 404, "{}"); return; }

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
        Integer id = asInt(obj.get("id"));
        String username = asString(obj.get("username"));
        String email = asString(obj.get("email"));
        String password = asString(obj.get("password"));

        if (id == null || username == null || email == null || password == null) { send(ex, 400, "{}"); return; }
        if (username.isEmpty() || email.isEmpty() || password.isEmpty()) { send(ex, 400, "{}"); return; }
        if (users.containsKey(id)) { send(ex, 409, "{}"); return; }

        String ph = Sha256.hexUpper(password);
        users.put(id, new User(id, username, email, ph));
        // Mark state as dirty so the background saver will persist
        dirty = true;
        send(ex, 200, SimpleJson.stringify(toMap(users.get(id), true)));
    }

    private static void handleUpdate(HttpExchange ex, Map<String,Object> obj) throws IOException {
        Integer id = asInt(obj.get("id"));
        if (id == null) { send(ex, 400, "{}"); return; }

        User u = users.get(id);
        if (u == null) { send(ex, 404, "{}"); return; }

        boolean any = false;

        if (obj.containsKey("username")) {
            String username = asString(obj.get("username"));
            if (username == null || username.isEmpty()) { send(ex, 400, "{}"); return; }
            u.username = username;
            any = true;
        }
        if (obj.containsKey("email")) {
            String email = asString(obj.get("email"));
            if (email == null || email.isEmpty()) { send(ex, 400, "{}"); return; }
            u.email = email;
            any = true;
        }
        if (obj.containsKey("password")) {
            String pw = asString(obj.get("password"));
            if (pw == null || pw.isEmpty()) { send(ex, 400, "{}"); return; }
            u.passwordHash = Sha256.hexUpper(pw);
            any = true;
        }

        // IMPORTANT: if only required fields were provided (command + id), treat as no-op and return current record
        // This matches the expected testcase behavior.
        // Mark dirty so the background saver will persist
        dirty = true;
        send(ex, 200, SimpleJson.stringify(toMap(u, true)));
    }

    private static void handleDelete(HttpExchange ex, Map<String,Object> obj) throws IOException {
        Integer id = asInt(obj.get("id"));
        String username = asString(obj.get("username"));
        String email = asString(obj.get("email"));
        String password = asString(obj.get("password"));

        if (id == null || username == null || email == null || password == null) { send(ex, 400, "{}"); return; }

        User u = users.get(id);
        if (u == null) { send(ex, 404, "{}"); return; }

        String ph = Sha256.hexUpper(password);
        if (!u.username.equals(username) || !u.email.equals(email) || !u.passwordHash.equals(ph)) {
            send(ex, 401, "{}");
            return;
        }

        users.remove(id);
        // Mark dirty so the background saver will persist
        dirty = true;
        send(ex, 200, "{}");
    }

    private static Map<String,Object> toMap(User u, boolean includePassword) {
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("id", u.id);
        resp.put("username", u.username);
        resp.put("email", u.email);
        if (includePassword) resp.put("password", u.passwordHash);
        return resp;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static Integer parseInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return null; } }
    private static String asString(Object o) { return (o instanceof String) ? (String)o : null; }
    private static Integer asInt(Object o) { return (o instanceof Number) ? ((Number)o).intValue() : null; }

    /**
     * Load persisted users from disk into the in-memory map.  If the
     * file does not exist or cannot be parsed the map is left empty.
     */
    private static void loadUsers() {
        java.io.File f = new java.io.File(USERS_FILENAME);
        if (!f.exists()) return;
        try {
            String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (json.isEmpty()) return;
            Map<String,Object> root = SimpleJson.parseObject(json);
            for (Map.Entry<String,Object> entry : root.entrySet()) {
                try {
                    int uid = Integer.parseInt(entry.getKey());
                    Object val = entry.getValue();
                    if (!(val instanceof Map<?,?>)) continue;
                    Map<?,?> uMap = (Map<?,?>) val;
                    String username = null;
                    String email = null;
                    String passwordHash = null;
                    Object un = uMap.get("username");
                    if (un instanceof String) username = (String) un;
                    Object em = uMap.get("email");
                    if (em instanceof String) email = (String) em;
                    Object ph = uMap.get("passwordHash");
                    if (ph instanceof String) passwordHash = (String) ph;
                    if (username != null && email != null && passwordHash != null) {
                        users.put(uid, new User(uid, username, email, passwordHash));
                    }
                } catch (Exception ignored) {
                    // skip malformed entries
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load users: " + e);
        }
    }

    /**
     * Persist the current user map to disk.  The users are stored in a
     * JSON object keyed by their id.  Errors during writing are
     * silently ignored.
     */
    private static void saveUsers() {
        try {
            Map<String,Object> root = new LinkedHashMap<>();
            for (Map.Entry<Integer,User> entry : users.entrySet()) {
                User u = entry.getValue();
                Map<String,Object> obj = new LinkedHashMap<>();
                obj.put("id", u.id);
                obj.put("username", u.username);
                obj.put("email", u.email);
                obj.put("passwordHash", u.passwordHash);
                root.put(String.valueOf(entry.getKey()), obj);
            }
            String json = SimpleJson.stringify(root);
            java.nio.file.Files.write(new java.io.File(USERS_FILENAME).toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("Failed to save users: " + e);
        }
    }
}
