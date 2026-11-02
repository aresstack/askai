package com.aresstack.askai.groupchat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class MentionParserTest {

    @Test
    public void detectsBotMention() {
        assertTrue(MentionParser.mentionsBot("@AskAI summarize this"));
        assertTrue(MentionParser.mentionsBot("@askai hello"));
        assertTrue(MentionParser.mentionsBot("Hey @AskAI, what do you think?"));
        assertFalse(MentionParser.mentionsBot("No bot here"));
        assertFalse(MentionParser.mentionsBot("@Alice what is going on?"));
    }

    @Test
    public void extractsMentionedParticipantIds() {
        Participant alice = new Participant("alice-id", "Alice", null);
        Participant bob = new Participant("bob-id", "Bob", null);
        List<Participant> participants = Arrays.asList(alice, bob);

        List<String> mentioned = MentionParser.extractMentionedIds("@Alice what do you think?", participants);
        assertEquals(1, mentioned.size());
        assertEquals("alice-id", mentioned.get(0));
    }

    @Test
    public void extractsMultipleMentions() {
        Participant alice = new Participant("alice-id", "Alice", null);
        Participant bob = new Participant("bob-id", "Bob", null);
        List<Participant> participants = Arrays.asList(alice, bob);

        List<String> mentioned = MentionParser.extractMentionedIds("@Alice and @Bob let's meet", participants);
        assertEquals(2, mentioned.size());
        assertTrue(mentioned.contains("alice-id"));
        assertTrue(mentioned.contains("bob-id"));
    }

    @Test
    public void stripsTrailingPunctuation() {
        Participant alice = new Participant("alice-id", "Alice", null);
        List<Participant> participants = Collections.singletonList(alice);

        List<String> mentioned = MentionParser.extractMentionedIds("@Alice, are you there?", participants);
        assertEquals(1, mentioned.size());
        assertEquals("alice-id", mentioned.get(0));
    }

    @Test
    public void doesNotMatchBotAsParticipant() {
        // Even if a participant were named "AskAI", bot detection is separate.
        Participant bot = new Participant("bot-id", "AskAI", null);
        List<Participant> participants = Collections.singletonList(bot);

        List<String> mentioned = MentionParser.extractMentionedIds("@AskAI summarize", participants);
        // The method still matches display names — the caller is responsible for separating bot logic.
        assertEquals(1, mentioned.size());
        assertEquals("bot-id", mentioned.get(0));
    }

    @Test
    public void returnsEmptyForNullOrBlank() {
        assertTrue(MentionParser.extractMentionedIds(null, Collections.<Participant>emptyList()).isEmpty());
        assertTrue(MentionParser.extractMentionedIds("", Collections.<Participant>emptyList()).isEmpty());
        assertTrue(MentionParser.extractMentionedIds("hello", null).isEmpty());
    }

    @Test
    public void groupChatModeConstantsAreDistinct() {
        assertFalse(GroupChatMode.YAPPING.equals(GroupChatMode.QUESTING));
        assertFalse(GroupChatMode.YAPPING.equals(GroupChatMode.PARTYING));
        assertFalse(GroupChatMode.QUESTING.equals(GroupChatMode.PARTYING));
        assertEquals("builtin.partying", GroupChatMode.PARTYING);
    }

    // ------------------------------------------------------------------ mentionHandle matching

    @Test
    public void matchesByMentionHandleForSpacedDisplayNames() {
        // "Alice Smith" → handle "AliceSmith"
        Participant alice = new Participant("alice-id", "Alice Smith", "AliceSmith", null);
        List<Participant> participants = Collections.singletonList(alice);

        // @AliceSmith is the handle — should resolve
        List<String> matched = MentionParser.extractMentionedIds("@AliceSmith can you help?", participants);
        assertEquals(1, matched.size());
        assertEquals("alice-id", matched.get(0));

        // @Alice alone should NOT match (display name has a space so fallback does not apply)
        List<String> noMatch = MentionParser.extractMentionedIds("@Alice can you help?", participants);
        assertTrue(noMatch.isEmpty());
    }

    @Test
    public void matchIsCaseInsensitiveOnHandle() {
        Participant alice = new Participant("alice-id", "Alice", "AliceSmith", null);
        List<Participant> participants = Collections.singletonList(alice);

        List<String> matched = MentionParser.extractMentionedIds("@alicesmith what do you think?", participants);
        assertEquals(1, matched.size());
        assertEquals("alice-id", matched.get(0));
    }

    // ------------------------------------------------------------------ computeUniqueHandle

    @Test
    public void computeUniqueHandleStripsSpaces() {
        String handle = MentionParser.computeUniqueHandle("Alice Smith", Collections.<String>emptyList());
        assertEquals("AliceSmith", handle);
    }

    @Test
    public void computeUniqueHandleResolvesCollisions() {
        List<String> existing = Arrays.asList("Alice", "Alice2");
        String handle = MentionParser.computeUniqueHandle("Alice", existing);
        assertEquals("Alice3", handle);
    }

    @Test
    public void computeUniqueHandleNeverReturnsAskAI() {
        // "AskAI" is reserved for the bot; a human participant named "AskAI" gets a suffix.
        String handle = MentionParser.computeUniqueHandle("AskAI", Collections.<String>emptyList());
        assertFalse("AskAI".equalsIgnoreCase(handle));
        assertFalse(handle.isEmpty());
    }

    @Test
    public void computeUniqueHandleFallsBackForAllNonAlphanumeric() {
        String handle = MentionParser.computeUniqueHandle("!@#$", Collections.<String>emptyList());
        assertFalse(handle.isEmpty());
    }
}

