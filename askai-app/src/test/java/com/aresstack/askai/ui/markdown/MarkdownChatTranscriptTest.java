package com.aresstack.askai.ui.markdown;

import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verify transcript ordering, clearing, single active assistant, info plain-text and EDT enforcement. */
public class MarkdownChatTranscriptTest {

    @Test
    public void insertsUserAndAssistantMessagesInOrder() throws Exception {
        MarkdownChatTranscript transcript = onEdt(new java.util.concurrent.Callable<MarkdownChatTranscript>() {
            public MarkdownChatTranscript call() {
                MarkdownChatTranscript t = new MarkdownChatTranscript();
                t.appendUser("Hello");
                t.startAssistant("model");
                t.appendAssistantDelta("Hi **there**");
                t.finishAssistant();
                return t;
            }
        });
        assertFalse("not empty after messages", isEmptyOnEdt(transcript));
        assertTrue("assistant view present",
                MarkdownTestSupport.containsType(view(transcript), MarkdownMessageView.class));
    }

    @Test
    public void clearRemovesAllMessages() throws Exception {
        final MarkdownChatTranscript transcript = onEdt(new java.util.concurrent.Callable<MarkdownChatTranscript>() {
            public MarkdownChatTranscript call() {
                MarkdownChatTranscript t = new MarkdownChatTranscript();
                t.appendUser("Hello");
                t.startAssistant("model");
                t.appendAssistantDelta("Hi");
                t.finishAssistant();
                t.clear();
                return t;
            }
        });
        assertTrue("empty after clear", isEmptyOnEdt(transcript));
        assertFalse(MarkdownTestSupport.containsType(view(transcript), MarkdownMessageView.class));
    }

    @Test
    public void deltaWithoutStartCreatesAnAssistantMessage() throws Exception {
        MarkdownChatTranscript transcript = onEdt(new java.util.concurrent.Callable<MarkdownChatTranscript>() {
            public MarkdownChatTranscript call() {
                MarkdownChatTranscript t = new MarkdownChatTranscript();
                t.appendAssistantDelta("orphan delta");
                return t;
            }
        });
        assertTrue("delta created one assistant message",
                MarkdownTestSupport.collect(view(transcript), MarkdownMessageView.class).size() == 1);
    }

    @Test
    public void infoStaysPlainTextNotMarkdown() throws Exception {
        MarkdownChatTranscript transcript = onEdt(new java.util.concurrent.Callable<MarkdownChatTranscript>() {
            public MarkdownChatTranscript call() {
                MarkdownChatTranscript t = new MarkdownChatTranscript();
                t.appendInfo("**not bold**");
                return t;
            }
        });
        boolean literal = false;
        for (JLabel label : MarkdownTestSupport.collect(view(transcript), JLabel.class)) {
            if (label.getText() != null && label.getText().contains("**not bold**")) {
                literal = true;
            }
        }
        assertTrue("info shown literally, not interpreted as markdown", literal);
        assertFalse("info is not a markdown message view",
                MarkdownTestSupport.containsType(view(transcript), MarkdownMessageView.class));
    }

    @Test
    public void mutatingOffTheEventDispatchThreadIsRejected() throws Exception {
        final MarkdownChatTranscript transcript =
                onEdt(new java.util.concurrent.Callable<MarkdownChatTranscript>() {
                    public MarkdownChatTranscript call() {
                        return new MarkdownChatTranscript();
                    }
                });
        final AtomicBoolean rejected = new AtomicBoolean(false);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    transcript.appendUser("off edt");
                } catch (IllegalStateException expected) {
                    rejected.set(true);
                }
            }
        });
        worker.start();
        worker.join(2000);
        assertTrue("off-EDT mutation throws", rejected.get());
    }

    // ------------------------------------------------------------------ helpers

    private static Component view(MarkdownChatTranscript transcript) {
        JScrollPane scrollPane = (JScrollPane) transcript.getComponent();
        return scrollPane.getViewport().getView();
    }

    private static boolean isEmptyOnEdt(final MarkdownChatTranscript transcript) throws Exception {
        final boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                result[0] = transcript.isEmpty();
            }
        });
        return result[0];
    }

    private static <T> T onEdt(final java.util.concurrent.Callable<T> callable) throws Exception {
        final Object[] box = new Object[1];
        final Exception[] error = new Exception[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    try {
                        box[0] = callable.call();
                    } catch (Exception ex) {
                        error[0] = ex;
                    }
                }
            });
        } catch (InvocationTargetException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
        if (error[0] != null) {
            throw error[0];
        }
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }
}
