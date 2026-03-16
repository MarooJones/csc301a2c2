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
