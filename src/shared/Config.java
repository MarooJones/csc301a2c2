import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Config {
    public final Map<String, Service> services = new HashMap<>();
    public static final class Service {
        public final String ip;
        public final int port;
        public Service(String ip, int port){ this.ip = ip; this.port = port; }
    }

    public static Config load(String path) throws IOException {
        String s = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        Map<String,Object> root = SimpleJson.parseObject(s);
        Config cfg = new Config();
        for (var e : root.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String,Object> m = (Map<String,Object>) e.getValue();
            String ip = (m.get("ip") instanceof String) ? (String)m.get("ip") : null;
            Integer port = (m.get("port") instanceof Number) ? ((Number)m.get("port")).intValue() : null;
            if (ip != null && port != null) cfg.services.put(e.getKey(), new Service(ip, port));
        }
        return cfg;
    }

    public Service get(String name) { return services.get(name); }
}
