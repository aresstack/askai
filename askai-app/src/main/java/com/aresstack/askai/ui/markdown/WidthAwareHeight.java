package com.aresstack.askai.ui.markdown;

/**
 * Implemented by components whose height depends on the width they will be given (wrapping Markdown).
 *
 * <p>A host that already knows the target width (a chat bubble, a transcript row) calls
 * {@link #preferredHeightForWidth(int)} to obtain the correct height in a single, deterministic step —
 * instead of setting a size and hoping a later Swing layout pass recomputes the wrapped height.
 */
public interface WidthAwareHeight {

    /** @return the preferred height this component needs when laid out at exactly {@code width} pixels. */
    int preferredHeightForWidth(int width);
}
