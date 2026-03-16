#!/usr/bin/env bash
set -euo pipefail

# 1) Make SHA-256 hex UPPERCASE (matches user_responses.json)
cat > src/shared/Sha256.java <<'JAVA'
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Sha256 {
    public static String hexUpper(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02X", b)); // UPPERCASE
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
JAVA

# 2) Patch UserService to call hexUpper instead of lowercase
perl -0777 -i -pe 's/Sha256\.hexLower\(/Sha256.hexUpper(/g' src/UserService/UserService.java

# 3) Implement workload parser (python3, no external libs)
cat > compiled/workload_parser.py <<'PY'
#!/usr/bin/env python3
import sys, json, urllib.request, urllib.error

def http(method: str, url: str, body: dict | None):
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            code = resp.getcode()
            txt = resp.read().decode("utf-8")
            return code, txt
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8") if e.fp else ""
        return e.code, txt
    except Exception as e:
        return 0, str(e)

def parse_kv(token: str):
    if ":" not in token:
        return None, None
    k, v = token.split(":", 1)
    return k.strip(), v.strip()

def main():
    if len(sys.argv) != 3:
        print("Usage: workload_parser.py <workloadfile> <config.json>", file=sys.stderr)
        sys.exit(2)

    workloadfile = sys.argv[1]
    configfile = sys.argv[2]

    with open(configfile, "r", encoding="utf-8") as f:
        cfg = json.load(f)

    osvc = cfg["OrderService"]
    base = f'http://{osvc["ip"]}:{osvc["port"]}'

    with open(workloadfile, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line:
                continue
            if line.startswith("#"):
                continue

            parts = line.split()
            if len(parts) < 2:
                continue

            kind = parts[0].upper()
            action = parts[1].lower()

            # USER
            if kind == "USER":
                if action == "create" and len(parts) >= 6:
                    _,_, sid, username, email, password = parts[:6]
                    body = {"command":"create","id":int(sid),"username":username,"email":email,"password":password}
                    code, txt = http("POST", base + "/user", body)
                elif action == "get" and len(parts) >= 3:
                    sid = parts[2]
                    code, txt = http("GET", base + f"/user/{int(sid)}", None)
                elif action == "update" and len(parts) >= 3:
                    sid = int(parts[2])
                    body = {"command":"update","id":sid}
                    for t in parts[3:]:
                        k,v = parse_kv(t)
                        if k in ("username","email","password") and v is not None:
                            body[k] = v
                    code, txt = http("POST", base + "/user", body)
                elif action == "delete" and len(parts) >= 6:
                    _,_, sid, username, email, password = parts[:6]
                    body = {"command":"delete","id":int(sid),"username":username,"email":email,"password":password}
                    code, txt = http("POST", base + "/user", body)
                else:
                    code, txt = 0, "SKIP (bad USER line)"

            # PRODUCT
            elif kind == "PRODUCT":
                if action == "create" and len(parts) >= 7:
                    # PRODUCT create <id> <name> <description> <price> <quantity>
                    _,_, sid, name, description, price, qty = parts[:7]
                    body = {"command":"create","id":int(sid),"name":name,"description":description,"price":float(price),"quantity":int(float(qty))}
                    code, txt = http("POST", base + "/product", body)
                elif action in ("info","get") and len(parts) >= 3:
                    sid = parts[2]
                    code, txt = http("GET", base + f"/product/{int(sid)}", None)
                elif action == "update" and len(parts) >= 3:
                    sid = int(parts[2])
                    body = {"command":"update","id":sid}
                    for t in parts[3:]:
                        k,v = parse_kv(t)
                        if k in ("name","description") and v is not None:
                            body[k] = v
                        elif k == "price" and v is not None:
                            body[k] = float(v)
                        elif k == "quantity" and v is not None:
                            body[k] = int(float(v))
                    code, txt = http("POST", base + "/product", body)
                elif action == "delete" and len(parts) >= 6:
                    # PRODUCT delete <id> <name> <price> <quantity>
                    _,_, sid, name, price, qty = parts[:6]
                    body = {"command":"delete","id":int(sid),"name":name,"price":float(price),"quantity":int(float(qty))}
                    code, txt = http("POST", base + "/product", body)
                else:
                    code, txt = 0, "SKIP (bad PRODUCT line)"

            # ORDER
            elif kind == "ORDER":
                if action == "place" and len(parts) >= 5:
                    # ORDER place <product_id> <user_id> <quantity>
                    _,_, pid, uid, qty = parts[:5]
                    body = {"command":"place order","product_id":int(pid),"user_id":int(uid),"quantity":int(float(qty))}
                    code, txt = http("POST", base + "/order", body)
                else:
                    code, txt = 0, "SKIP (bad ORDER line)"
            else:
                code, txt = 0, "SKIP (unknown kind)"

            # Output format: echo the workload line then the HTTP result
            print(line)
            print(f"HTTP {code}")
            print(txt if txt else "{}")
            print("-"*30)

if __name__ == "__main__":
    main()
PY
chmod +x compiled/workload_parser.py

# 4) Add -w option to runme.sh
perl -0777 -i -pe 's/\*\)\n\s*echo "Usage: \.\/runme\.sh -c \| -i \| -u \| -p \| -o"/-w)\n    (cd "$ROOT" && python3 "$ROOT\/compiled\/workload_parser.py" "$2" "$ROOT\/config.json")\n    ;;\n  *)\n    echo "Usage: .\/runme.sh -c | -i | -u | -p | -o | -w workloadfile"/s' runme.sh

echo "Step 6 written."
