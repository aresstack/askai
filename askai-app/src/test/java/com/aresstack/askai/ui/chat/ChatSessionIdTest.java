package com.aresstack.askai.ui.chat;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChatSessionIdTest {

    @Test
    public void createGeneratesUniqueIds() {
        assertNotEquals(ChatSessionId.create(), ChatSessionId.create());
    }

    @Test
    public void equalityAndHashFollowTheUuid() {
        UUID uuid = UUID.randomUUID();
        ChatSessionId a = new ChatSessionId(uuid);
        ChatSessionId b = new ChatSessionId(uuid);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(new ChatSessionId(UUID.randomUUID())));
    }

    @Test
    public void shortLabelKeepsThePrefixButFullIdStaysInToString() {
        ChatSessionId id = ChatSessionId.create();
        assertEquals(8, id.shortLabel().length());
        assertTrue(id.toString().startsWith(id.shortLabel()));
        assertEquals(36, id.toString().length());
    }

    @Test
    public void rejectsNull() {
        try {
            new ChatSessionId(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
