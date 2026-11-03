package com.aresstack.askai.research.host;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DataForSeoApiKeyTest {

    @Test
    public void theDashboardKeyIsBase64OfLoginAndPassword() {
        String key = java.util.Base64.getEncoder().encodeToString("me@example.com:s3cr:et".getBytes());
        DataForSeoApiKey parsed = DataForSeoApiKey.parse((" " + key + "\n").toCharArray());
        assertEquals("me@example.com", parsed.login);
        assertArrayEquals("s3cr:et".toCharArray(), parsed.password);
    }

    @Test
    public void rawLoginColonPasswordIsAcceptedToo() {
        DataForSeoApiKey parsed = DataForSeoApiKey.parse("me@example.com:pw".toCharArray());
        assertEquals("me@example.com", parsed.login);
        assertArrayEquals("pw".toCharArray(), parsed.password);
    }

    @Test
    public void emptyOrUnusableInputKeepsTheStoredCredential() {
        assertNull(DataForSeoApiKey.parse(null));
        assertNull(DataForSeoApiKey.parse("   ".toCharArray()));
        assertNull(DataForSeoApiKey.parse("not-a-key".toCharArray()));
    }
}
