package hu.blackbelt.osgi.filestore.servlet;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Security rules for a download response (change harden-native-file-preview). Pure, no servlet types, so every
 * rule is unit-testable.
 *
 * <p>Stored content types are the uploader's claim. A browser that opens a download URL directly would run an
 * uploaded HTML/SVG/XML file on the application origin, so such types are never served inline, and every
 * response is made inert with {@code nosniff} and a sandboxing CSP.
 */
public final class DownloadResponsePolicy {

    public static final String ATTACHMENT = "attachment";
    public static final String INLINE = "inline";
    public static final String CSP = "sandbox; default-src 'none'";
    public static final String NOSNIFF = "nosniff";

    private DownloadResponsePolicy() {
    }

    /** True for types a browser renders as an active document (or that cannot be trusted to be anything else). */
    public static boolean isActiveType(String contentType) {
        if (contentType == null) {
            return true;
        }
        String t = contentType.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.indexOf(',') >= 0) { // a list of types is not one media type
            return true;
        }
        int semi = t.indexOf(';');
        if (semi >= 0) {
            t = t.substring(0, semi).trim();
        }
        int slash = t.indexOf('/');
        if (slash <= 0 || slash == t.length() - 1 || t.indexOf('/', slash + 1) >= 0) {
            return true;
        }
        String sub = t.substring(slash + 1);
        return t.equals("text/html")
                || t.equals("application/xhtml+xml")
                || t.equals("image/svg+xml")
                || sub.equals("xml")
                || sub.endsWith("+xml");
    }

    /**
     * The disposition to send. {@code tokenDisposition} is authoritative when present and the URL may only
     * downgrade it to attachment. With no token claim the URL decides. Active types are always attachment.
     */
    public static String disposition(String tokenDisposition, String urlDisposition, String contentType) {
        String token = normalize(tokenDisposition);
        String url = normalize(urlDisposition);
        String result;
        if (token != null) {
            result = ATTACHMENT.equals(url) ? ATTACHMENT : token;
        } else {
            result = url != null ? url : ATTACHMENT;
        }
        return isActiveType(contentType) ? ATTACHMENT : result;
    }

    /** RFC 6266: ASCII fallback in {@code filename}, exact UTF-8 name in {@code filename*}. */
    public static String contentDisposition(String disposition, String fileName) {
        if (fileName == null) {
            return disposition;
        }
        StringBuilder ascii = new StringBuilder();
        for (char c : fileName.toCharArray()) {
            ascii.append(c < 0x20 || c > 0x7e || c == '"' || c == '\\' || c == '%' ? '_' : c);
        }
        return disposition + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + percentEncode(fileName);
    }

    private static String normalize(String d) {
        if (d == null) {
            return null;
        }
        String v = d.trim().toLowerCase(Locale.ROOT);
        return v.equals(INLINE) ? INLINE : ATTACHMENT; // anything unknown is the safe value
    }

    private static String percentEncode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }
}
