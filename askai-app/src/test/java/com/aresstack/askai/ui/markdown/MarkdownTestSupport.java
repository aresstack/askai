package com.aresstack.askai.ui.markdown;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for the markdown UI tests: recursively collect components from a Swing tree. */
final class MarkdownTestSupport {

    private MarkdownTestSupport() {
    }

    /** Collect every descendant component (and the root) that is an instance of {@code type}. */
    static <T> List<T> collect(Component root, Class<T> type) {
        List<T> found = new ArrayList<T>();
        collectInto(root, type, found);
        return found;
    }

    static <T> boolean containsType(Component root, Class<T> type) {
        return !collect(root, type).isEmpty();
    }

    private static <T> void collectInto(Component component, Class<T> type, List<T> found) {
        if (component == null) {
            return;
        }
        if (type.isInstance(component)) {
            found.add(type.cast(component));
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collectInto(child, type, found);
            }
        }
    }
}
