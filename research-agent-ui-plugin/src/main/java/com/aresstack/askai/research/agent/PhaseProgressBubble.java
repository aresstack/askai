package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentConversationSink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The ONE thought bubble a long running phase job shows in the research chat (live run 5, 2026-11-17): the search
 * plan of the Sources phase, the clustering of the Outline phase, the section writer of the Document phase. It opens
 * on the first refresh, updates in place at most once per refresh interval (a change inside the interval is shown when
 * it ends, never dropped), keeps the latest activity lines and closes with a summary. It only mirrors what the job
 * reports; it never calls a model. UI thread only, except the delayed flush, which goes through the UI executor.
 */
final class PhaseProgressBubble {

    /** The bubble's current title and counter text. */
    static final class Text {
        final String title;
        final String body;

        Text(String title, String body) {
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
        }
    }

    private final String idPrefix;
    private final Supplier<AgentConversationSink> sink;
    private final Executor ui;
    private final IntSupplier refreshMillis;
    private final IntSupplier activityLines;
    private final ArrayDeque<String> activity = new ArrayDeque<String>();
    private long sequence;
    private String bubbleId;
    private long shownAt;
    private boolean flushPending;
    private java.util.Timer timer;
    private Supplier<Text> lastText;

    PhaseProgressBubble(String idPrefix, Supplier<AgentConversationSink> sink, Executor ui,
                        IntSupplier refreshMillis, IntSupplier activityLines) {
        this.idPrefix = idPrefix;
        this.sink = sink;
        this.ui = ui;
        this.refreshMillis = refreshMillis;
        this.activityLines = activityLines;
    }

    /** Remembers one activity line (blank and repeated lines are ignored). */
    void note(String line) {
        if (line == null) {
            return;
        }
        String clean = line.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty() || clean.equals(activity.peekLast())) {
            return;
        }
        activity.addLast(clean);
        int keep = Math.max(0, activityLines.getAsInt());
        while (activity.size() > keep) {
            activity.removeFirst();
        }
    }

    /** The latest activity lines, oldest first. */
    List<String> activity() {
        return new ArrayList<String>(activity);
    }

    boolean isOpen() {
        return bubbleId != null;
    }

    /** Opens or updates the bubble with the text the supplier gives now (or when the interval ends). */
    void refresh(Supplier<Text> text) {
        AgentConversationSink target = sink.get();
        if (target == null || text == null) {
            return;
        }
        lastText = text;
        long interval = Math.max(1, refreshMillis.getAsInt());
        long now = System.currentTimeMillis();
        if (bubbleId != null && now - shownAt < interval) {
            scheduleFlush(interval - (now - shownAt));
            return;
        }
        Text shown = text.get();
        if (shown == null) {
            return;
        }
        if (bubbleId == null) {
            bubbleId = idPrefix + (++sequence);
            target.startThinking(bubbleId, shown.title);
        }
        StringBuilder body = new StringBuilder(shown.title);
        if (!shown.body.isEmpty()) {
            body.append('\n').append(shown.body);
        }
        for (String line : activity) {
            body.append("\n- ").append(line);
        }
        target.updateThinking(bubbleId, body.toString());
        shownAt = now;
    }

    private void scheduleFlush(long delayMillis) {
        if (flushPending) {
            return;
        }
        flushPending = true;
        if (timer == null) {
            timer = new java.util.Timer(idPrefix + "timer", true);
        }
        timer.schedule(new java.util.TimerTask() {
            public void run() {
                ui.execute(new Runnable() {
                    public void run() {
                        flushPending = false;
                        if (bubbleId != null && lastText != null) {
                            refresh(lastText);
                        }
                    }
                });
            }
        }, Math.max(1L, delayMillis));
    }

    /** Closes the bubble with the summary and forgets the activity; without an open bubble nothing happens. */
    void finish(String summary) {
        AgentConversationSink target = sink.get();
        if (bubbleId != null && target != null) {
            target.finishThinking(bubbleId, summary == null ? "" : summary);
        }
        bubbleId = null;
        lastText = null;
        activity.clear();
    }

    /** Stops the delayed flush (session close). */
    void close() {
        if (timer != null) {
            timer.cancel();
        }
    }
}
