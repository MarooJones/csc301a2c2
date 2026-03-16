import java.util.*;

public final class SimpleJson {
    public static Map<String, Object> parseObject(String s) {
        Parser p = new Parser(s);
        Object v = p.parseValue();
        if (!(v instanceof Map)) throw new IllegalArgumentException("Not a JSON object");
        @SuppressWarnings("unchecked")
        Map<String,Object> m = (Map<String,Object>) v;
        return m;
    }

    public static String stringify(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return quote((String)v);
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return ((Boolean)v) ? "true" : "false";
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String,Object> m = (Map<String,Object>) v;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(quote(e.getKey())).append(":").append(stringify(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> a = (List<Object>) v;
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object x : a) {
                if (!first) sb.append(",");
                first = false;
                sb.append(stringify(x));
            }
            sb.append("]");
            return sb.toString();
        }
        throw new IllegalArgumentException("Unsupported type: " + v.getClass());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private static final class Parser {
        private final String s;
        private int i = 0;
        Parser(String s){ this.s = s; }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) throw err("Unexpected EOF");
            char c = s.charAt(i);
            if (c == '{') return parseObj();
            if (c == '[') return parseArr();
            if (c == '"') return parseStr();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') return parseNull();
            if (c == '-' || (c >= '0' && c <= '9')) return parseNum();
            throw err("Unexpected char: " + c);
        }

        Map<String,Object> parseObj() {
            expect('{');
            Map<String,Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) { i++; return m; }
            while (true) {
                skipWs();
                String k = parseStr();
                skipWs(); expect(':');
                Object v = parseValue();
                m.put(k, v);
                skipWs();
                if (peek('}')) { i++; return m; }
                expect(',');
            }
        }

        List<Object> parseArr() {
            expect('[');
            List<Object> a = new ArrayList<>();
            skipWs();
            if (peek(']')) { i++; return a; }
            while (true) {
                a.add(parseValue());
                skipWs();
                if (peek(']')) { i++; return a; }
                expect(',');
            }
        }

        String parseStr() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("Bad escape");
                    char e = s.charAt(i++);
                    if (e == '"' || e == '\\' || e == '/') sb.append(e);
                    else if (e == 'n') sb.append('\n');
                    else if (e == 'r') sb.append('\r');
                    else if (e == 't') sb.append('\t');
                    else throw err("Unsupported escape");
                } else sb.append(c);
            }
            throw err("Unterminated string");
        }

        Boolean parseBool() {
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            throw err("Bad boolean");
        }

        Object parseNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("Bad null");
        }

        Number parseNum() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean isFloat = false;
            if (i < s.length() && s.charAt(i) == '.') {
                isFloat = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            return isFloat ? Double.parseDouble(num) : Long.parseLong(num);
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        boolean peek(char c){ return (i < s.length() && s.charAt(i) == c); }
        void expect(char c){
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) throw err("Expected '" + c + "'");
            i++;
        }
        IllegalArgumentException err(String msg){ return new IllegalArgumentException(msg + " at " + i); }
    }
}
