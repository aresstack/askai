package com.aresstack.askai.ui;

import com.aresstack.askai.vision.ImageAttachment;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The attachment strip owns the queued images, de-duplicates by file, hides when empty and notifies. */
public class ChatAttachmentStripTest {

    private static final ImageAttachment A = ImageAttachment.of(new File("a.png"));
    private static final ImageAttachment B = ImageAttachment.of(new File("b.png"));

    @Test
    public void addRemoveClearAndNotify() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                final AtomicInteger changes = new AtomicInteger();
                ChatAttachmentStrip strip = new ChatAttachmentStrip(new ChatAttachmentStrip.ChangeListener() {
                    public void onAttachmentsChanged() {
                        changes.incrementAndGet();
                    }
                });

                assertTrue(strip.isEmpty());
                assertFalse("hidden while empty", strip.isVisible());

                strip.addAttachments(Arrays.asList(A, B));
                assertEquals(2, strip.count());
                assertTrue("shown once it holds attachments", strip.isVisible());
                assertEquals(1, changes.get());

                strip.addAttachments(Arrays.asList(A)); // duplicate -> no change, no notify
                assertEquals(2, strip.count());
                assertEquals(1, changes.get());

                strip.removeAttachment(A);
                assertEquals(1, strip.count());
                assertEquals(2, changes.get());

                strip.clear();
                assertTrue(strip.isEmpty());
                assertFalse(strip.isVisible());
                assertEquals(3, changes.get());
            }
        });
    }
}
