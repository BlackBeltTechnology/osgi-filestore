package hu.blackbelt.osgi.filestore.servlet;

import static hu.blackbelt.osgi.filestore.servlet.DownloadResponsePolicy.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DownloadResponsePolicyTest {

    @Test
    void activeTypesAreNeverInline() {
        for (String t : new String[] { "text/html", "TEXT/HTML; charset=utf-8", "application/xhtml+xml", "image/svg+xml",
                "text/xml", "application/xml", "application/atom+xml", "", "  ", null, "video/mp4,text/html", "html", "a/b/c", "video/" }) {
            assertTrue(isActiveType(t), "active: " + t);
            assertEquals(ATTACHMENT, disposition(INLINE, INLINE, t), "inline requested for " + t);
        }
    }

    @Test
    void passiveTypesKeepInline() {
        for (String t : new String[] { "application/pdf", "image/png", "video/mp4", "text/plain", "text/csv", "application/octet-stream" }) {
            assertFalse(isActiveType(t), "passive: " + t);
        }
        assertEquals(INLINE, disposition(INLINE, null, "application/pdf"));
    }

    @Test
    void urlCanOnlyDowngrade() {
        assertEquals(ATTACHMENT, disposition(ATTACHMENT, INLINE, "application/pdf"));
        assertEquals(ATTACHMENT, disposition(INLINE, ATTACHMENT, "application/pdf"));
        assertEquals(INLINE, disposition(INLINE, INLINE, "application/pdf"));
        assertEquals(ATTACHMENT, disposition(INLINE, "bogus", "application/pdf"));
    }

    @Test
    void noTokenClaimUrlDecidesDefaultAttachment() {
        assertEquals(INLINE, disposition(null, INLINE, "image/png"));
        assertEquals(ATTACHMENT, disposition(null, null, "image/png"));
    }

    @Test
    void filenameIsRfc6266() {
        String h = contentDisposition(ATTACHMENT, "ár\"lista.pdf");
        assertEquals("attachment; filename=\"_r_lista.pdf\"; filename*=UTF-8''%C3%A1r%22lista.pdf", h);
        String evil = contentDisposition(INLINE, "a\"; x=\r\nSet-Cookie: y\\.pdf");
        assertFalse(evil.contains("\r") || evil.contains("\n"));
        assertEquals("attachment", contentDisposition("attachment", null));
    }
}
