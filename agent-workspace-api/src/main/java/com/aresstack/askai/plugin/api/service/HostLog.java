package com.aresstack.askai.plugin.api.service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The host's console log switchboard: every console line that starts with a category in square brackets
 * ({@code [browser] ...}, {@code [local-runtime] ...}) belongs to that category, and each category can be
 * switched off at runtime (Settings &gt; General &gt; Logging). Lines without a leading category (stack
 * traces, third-party output) always pass.
 *
 * <p>The host installs the filter once on {@link System#out} and {@link System#err}
 * ({@link #installConsoleFilter()}), so existing {@code System.err.println("[tag] ...")} calls in the host,
 * in plugins and in bundled runtimes are covered without each of them holding a logger. Plugins may also log
 * through {@link #log(String, String)} or ask {@link #isEnabled(String)} before building an expensive line.
 * This class lives in the host-provided API jar, so host and plugins share ONE state.
 */
public final class HostLog {

    /** {@code [category]} at the very start of a line; a following {@code [WARN]} etc. is part of the text. */
    private static final Pattern CATEGORY = Pattern.compile("^\\[([a-z][a-z0-9-]*)\\]");

    /**
     * Categories the code base logs under (collected from the sources), so Settings can offer them before
     * they first appear. Categories first seen at runtime are added automatically.
     */
    public static final List<String> KNOWN_CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            "advice", "agent-host", "agent-runtime", "app-icon", "askai", "browser", "browser-hud", "bubble-geom",
            "camoufox-prefetch", "chat-launcher", "chatgpt-connector", "data-migration", "engines",
            "initial-search", "layout-profiles", "local-runtime", "manual-search", "plugin", "probe-gen",
            "read-aloud", "reranker", "research", "research-agent", "research-bot", "research-host",
            "research-journal", "research-knowledge", "research-runtime", "research-scope", "scope-anchors",
            "scope-approve", "scope-check", "search-browser", "shutdown", "team-agent", "text-analysis", "tts",
            "vision", "visualizer", "web-search"));

    private static final Set<String> categories = new TreeSet<String>(KNOWN_CATEGORIES);
    private static final Set<String> disabled = new TreeSet<String>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();
    private static volatile boolean installed;

    private HostLog() {
    }

    /** Extracts the leading category of a console line, or {@code null} when the line has none. */
    public static String categoryOf(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = CATEGORY.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** @return whether lines of this category are printed; a {@code null} category is always enabled. */
    public static boolean isEnabled(String category) {
        if (category == null) {
            return true;
        }
        synchronized (HostLog.class) {
            return !disabled.contains(category);
        }
    }

    /** Whether this console line would be printed; registers its category when it is new. */
    public static boolean accepts(String line) {
        String category = categoryOf(line);
        if (category == null) {
            return true;
        }
        register(category);
        return isEnabled(category);
    }

    /** Prints {@code [category] message} on the console when the category is enabled. */
    public static void log(String category, String message) {
        System.err.println("[" + category + "] " + message);
    }

    /** Switches one category on or off; effective immediately. */
    public static void setEnabled(String category, boolean enabled) {
        if (category == null || category.trim().isEmpty()) {
            return;
        }
        boolean changed;
        synchronized (HostLog.class) {
            categories.add(category);
            changed = enabled ? disabled.remove(category) : disabled.add(category);
        }
        if (changed) {
            fireChanged();
        }
    }

    /** Replaces the set of switched-off categories (e.g. from the stored settings). */
    public static void setDisabled(Collection<String> switchedOff) {
        synchronized (HostLog.class) {
            disabled.clear();
            if (switchedOff != null) {
                for (String category : switchedOff) {
                    if (category != null && !category.trim().isEmpty()) {
                        disabled.add(category.trim());
                        categories.add(category.trim());
                    }
                }
            }
        }
        fireChanged();
    }

    /** @return the switched-off categories, sorted. */
    public static List<String> disabledCategories() {
        synchronized (HostLog.class) {
            return new ArrayList<String>(disabled);
        }
    }

    /** @return every known category (from the sources, the settings and what was logged so far), sorted. */
    public static List<String> categories() {
        synchronized (HostLog.class) {
            return new ArrayList<String>(categories);
        }
    }

    /** Called (on the logging thread) when a category appears or is switched. */
    public static void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public static void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Installs the category filter on {@link System#out} and {@link System#err}; idempotent. */
    public static synchronized void installConsoleFilter() {
        if (installed) {
            return;
        }
        installed = true;
        System.setOut(filtered(System.out));
        System.setErr(filtered(System.err));
    }

    /** Wraps a stream so that lines of switched-off categories are dropped. */
    public static PrintStream filtered(PrintStream target) {
        Charset charset = charsetOf(target);
        try {
            return new PrintStream(new CategoryFilterStream(target, charset), true, charset.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            return new PrintStream(new CategoryFilterStream(target, charset), true);
        }
    }

    /** The stream's own encoding ({@code PrintStream.charset()}, Java 18+), else the platform default. */
    static Charset charsetOf(PrintStream stream) {
        try {
            Object charset = PrintStream.class.getMethod("charset").invoke(stream);
            if (charset instanceof Charset) {
                return (Charset) charset;
            }
        } catch (Exception olderJava) {
            // Java 8-17: no charset(); the console stream uses the default charset there
        }
        return Charset.defaultCharset();
    }

    private static void register(String category) {
        boolean added;
        synchronized (HostLog.class) {
            added = categories.add(category);
        }
        if (added) {
            fireChanged();
        }
    }

    private static void fireChanged() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // a broken listener must never break logging
            }
        }
    }

    /** Buffers per thread up to the line end, then forwards or drops the whole line. */
    static final class CategoryFilterStream extends OutputStream {
        private static final int MAX_BUFFER = 64 * 1024;
        private final PrintStream target;
        private final Charset charset;
        /** Decision for the line in progress when it was already judged (an oversized line); null = not yet. */
        private final ThreadLocal<Boolean> lineDecision = new ThreadLocal<Boolean>();
        private final ThreadLocal<ByteArrayOutputStream> pending = new ThreadLocal<ByteArrayOutputStream>() {
            @Override
            protected ByteArrayOutputStream initialValue() {
                return new ByteArrayOutputStream(256);
            }
        };

        CategoryFilterStream(PrintStream target, Charset charset) {
            this.target = target;
            this.charset = charset;
        }

        @Override
        public void write(int b) {
            ByteArrayOutputStream line = pending.get();
            line.write(b);
            if (b == '\n') {
                emit(line, true);
            } else if (line.size() >= MAX_BUFFER) {
                emit(line, false);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            int start = offset;
            int end = offset + length;
            ByteArrayOutputStream line = pending.get();
            for (int i = offset; i < end; i++) {
                if (bytes[i] == '\n') {
                    line.write(bytes, start, i + 1 - start);
                    emit(line, true);
                    start = i + 1;
                }
            }
            if (start < end) {
                line.write(bytes, start, end - start);
                if (line.size() >= MAX_BUFFER) {
                    emit(line, false);
                }
            }
        }

        @Override
        public void flush() {
            // partial lines wait for their end so the category can be judged; only the target is flushed
            target.flush();
        }

        private void emit(ByteArrayOutputStream line, boolean lineEnded) {
            byte[] bytes = line.toByteArray();
            line.reset();
            Boolean decision = lineDecision.get();
            if (decision == null) {
                decision = accepts(new String(bytes, 0, Math.min(bytes.length, 128), charset));
            }
            if (lineEnded) {
                lineDecision.remove();
            } else {
                lineDecision.set(decision);
            }
            if (decision) {
                target.write(bytes, 0, bytes.length);
                target.flush();
            }
        }
    }
}
