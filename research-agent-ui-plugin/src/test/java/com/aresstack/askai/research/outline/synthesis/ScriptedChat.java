package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** A model that answers from a script, step by step, and records what it was asked. */
final class ScriptedChat implements MainModelChat {

    /** One answer: a text, or a failure when {@code text} is null. */
    interface Answer {
        MainModelChatResult answer(List<ChatMessage> messages, String schema);
    }

    static final class Call {
        final List<ChatMessage> messages;
        final String schema;
        final int maxTokens;

        Call(List<ChatMessage> messages, String schema, int maxTokens) {
            this.messages = new ArrayList<ChatMessage>(messages);
            this.schema = schema;
            this.maxTokens = maxTokens;
        }

        String prompt() {
            return messages.get(1).getContent();
        }

        String last() {
            return messages.get(messages.size() - 1).getContent();
        }
    }

    final LinkedList<Answer> script = new LinkedList<Answer>();
    final List<Call> calls = new ArrayList<Call>();
    int contextTokens;

    ScriptedChat then(final String text) {
        script.add(new Answer() {
            public MainModelChatResult answer(List<ChatMessage> messages, String schema) {
                return MainModelChatResult.ok(text);
            }
        });
        return this;
    }

    ScriptedChat then(Answer answer) {
        script.add(answer);
        return this;
    }

    ScriptedChat thenFailure(final String detail) {
        script.add(new Answer() {
            public MainModelChatResult answer(List<ChatMessage> messages, String schema) {
                return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, detail);
            }
        });
        return this;
    }

    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        return completeJson(messages, temperature, maxOutputTokens, null);
    }

    public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                            String schemaJson) {
        calls.add(new Call(messages, schemaJson, maxOutputTokens));
        if (script.isEmpty()) {
            throw new AssertionError("unexpected model call " + calls.size() + ": " + messages.get(1).getContent());
        }
        return script.removeFirst().answer(messages, schemaJson);
    }

    public int contextTokens() {
        return contextTokens;
    }

    public String modelName() {
        return "scripted";
    }
}
