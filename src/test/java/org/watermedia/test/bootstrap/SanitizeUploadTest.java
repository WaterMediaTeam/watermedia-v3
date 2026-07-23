package org.watermedia.test.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// GUARDS THE ONLY BARRIER STOPPING SESSION/ACCESS TOKENS FROM BEING POSTED TO THE PUBLIC mclo.gs API.
// A REGEX REGRESSION IN sanitizeUpload() WOULD SILENTLY LEAK CREDENTIALS IN EVERY GENERATED ISSUE REPORT.
class SanitizeUploadTest {

    // REFLECTION KEEPS sanitizeUpload() PRIVATE; INVOKING IT ONLY TRIGGERS WaterMediaApp's OWN CLASS INIT,
    // WHICH TOUCHES NO GL/NATIVE CODE (THE SECRET PATTERNS ARE PLAIN static final Pattern FIELDS).
    private static String sanitize(final String content) throws Exception {
        final Method method = Class.forName("org.watermedia.bootstrap.app.WaterMediaApp")
                .getDeclaredMethod("sanitizeUpload", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, content);
    }

    @Test
    void masksJwtAccessTokens() throws Exception {
        final String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        final String out = sanitize("accessToken=" + jwt + " loaded");
        assertFalse(out.contains(jwt), "raw JWT must not survive");
        assertTrue(out.contains("********"), "a mask must be present");
    }

    @Test
    void masksLaunchArgs() throws Exception {
        assertFalse(sanitize("java --accessToken s3cr3tValue --version 1.20").contains("s3cr3tValue"));
        assertFalse(sanitize("game --session token:abc123def:uuid-here").contains("abc123def"));
    }

    @Test
    void masksBareSessionTriple() throws Exception {
        final String out = sanitize("Session ID is token:deadbeefcafe:0d8a-11ec trailing");
        assertFalse(out.contains("deadbeefcafe"), "legacy session token must be masked");
    }

    @Test
    void masksAuthorizationHeader() throws Exception {
        assertFalse(sanitize("Authorization: Bearer abcdef123456").contains("abcdef123456"));
        assertFalse(sanitize("authorization=Zm9vYmFyQmF6").contains("Zm9vYmFyQmF6"));
    }

    @Test
    void masksKeyValueSecrets() throws Exception {
        assertFalse(sanitize("password=hunter2").contains("hunter2"));
        assertFalse(sanitize("\"api_key\":\"AIzaSyExampleKey\"").contains("AIzaSyExampleKey"));
        assertFalse(sanitize("client_secret = mySuperSecret").contains("mySuperSecret"));
    }

    @Test
    void leavesOrdinaryTextUntouched() throws Exception {
        final String clean = "This is a normal log line with no secrets at timestamp 12:34:56.";
        assertEquals(clean, sanitize(clean));
    }

    @Test
    void handlesNullAndEmpty() throws Exception {
        assertEquals("", sanitize(""));
    }
}
