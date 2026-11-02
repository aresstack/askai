package com.aresstack.askai.ui.markdown;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for {@link MermaidImageRenderer}: records calls without a real GraalJS/Batik render. */
final class FakeMermaidImageRenderer implements MermaidImageRenderer {

    final AtomicInteger calls = new AtomicInteger();
    final CountDownLatch firstCall = new CountDownLatch(1);
    volatile boolean lastCallOnEventDispatchThread;
    volatile boolean fail;
    volatile BufferedImage result = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

    @Override
    public BufferedImage render(String diagramCode, int width) {
        calls.incrementAndGet();
        lastCallOnEventDispatchThread = SwingUtilities.isEventDispatchThread();
        firstCall.countDown();
        if (fail) {
            throw new RuntimeException("mermaid render failed (test)");
        }
        return result;
    }
}
