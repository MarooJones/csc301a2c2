#!/usr/bin/env bash
set -euo pipefail

# --- Fix UserService: GET includes password, UPDATE with only id is allowed (no-op) ---
cat > src/UserService/UserService.java <<'JAVA'
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class UserService {
    private static final Map<Integer, User> users = new HashMap<>();

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

        HttpServer server = HttpServer.create(new InetSocketAddress(me.port), 0);
        server.createContext("/user", UserService::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("UserService listening on " + me.ip + ":" + me.port);
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
}
JAVA

# --- Fix ProductService: delete mismatch should return 404 (not 401) ---
perl -0777 -i -pe 's/send\(ex, 401, "\{\}"\); return;/send(ex, 404, "{}"); return;/g' src/ProductService/ProductService.java

echo "Step 7 written."
