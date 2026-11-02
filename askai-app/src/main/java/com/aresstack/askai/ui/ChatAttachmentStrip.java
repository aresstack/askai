package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.vision.ImageAttachment;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.FlowLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The row of queued image attachments shown above the chat editor. Each attachment is a small chip with a
 * best-effort thumbnail, its file name and a remove (×) button. The strip owns the attachment list, keeps
 * it de-duplicated by file, and notifies a listener whenever it changes so the composer can refresh the
 * send state and the attachment count. It holds only {@link ImageAttachment} references — never image
 * bytes; the thumbnail is loaded lazily off the EDT and simply falls back to a placeholder when a format
 * (e.g. WebP) cannot be decoded.
 */
final class ChatAttachmentStrip extends JPanel {

    /** Notified after any change to the attachment set (add, remove, clear). */
    interface ChangeListener {
        void onAttachmentsChanged();
    }

    private static final int THUMB = 44;
    private static final Color CHIP_BG = ThemeColor.of("chat.attachmentChipBackground");
    private static final Color CHIP_BORDER = ThemeColor.of("chat.attachmentChipBorder");

    private final List<ImageAttachment> attachments = new ArrayList<ImageAttachment>();
    private final ChangeListener changeListener;

    ChatAttachmentStrip(ChangeListener changeListener) {
        this.changeListener = changeListener;
        setOpaque(false);
        setLayout(new FlowLayout(FlowLayout.LEFT, 6, 4));
        setVisible(false);
    }

    /** Add attachments (ignoring ones already queued by file), then rebuild and notify. */
    void addAttachments(List<ImageAttachment> toAdd) {
        boolean changed = false;
        for (ImageAttachment attachment : toAdd) {
            if (!attachments.contains(attachment)) {
                attachments.add(attachment);
                changed = true;
            }
        }
        if (changed) {
            rebuild();
        }
    }

    void removeAttachment(ImageAttachment attachment) {
        if (attachments.remove(attachment)) {
            rebuild();
        }
    }

    void clear() {
        if (!attachments.isEmpty()) {
            attachments.clear();
            rebuild();
        }
    }

    List<ImageAttachment> getAttachments() {
        return Collections.unmodifiableList(new ArrayList<ImageAttachment>(attachments));
    }

    boolean isEmpty() {
        return attachments.isEmpty();
    }

    int count() {
        return attachments.size();
    }

    private void rebuild() {
        removeAll();
        for (ImageAttachment attachment : attachments) {
            add(createChip(attachment));
        }
        setVisible(!attachments.isEmpty());
        revalidate();
        repaint();
        if (changeListener != null) {
            changeListener.onAttachmentsChanged();
        }
    }

    private Component createChip(final ImageAttachment attachment) {
        JPanel chip = new JPanel(new BorderLayout(6, 0)) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CHIP_BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.setColor(CHIP_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
            }
        };
        chip.setOpaque(false);
        chip.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        chip.add(new ImageThumbnail(attachment, THUMB), BorderLayout.WEST);

        JLabel name = new JLabel(ellipsize(attachment.getDisplayName(), 18));
        name.setToolTipText(attachment.getDisplayName());
        chip.add(name, BorderLayout.CENTER);

        JButton remove = new ComicButton("×");
        remove.setToolTipText("Remove " + attachment.getDisplayName());
        remove.setFocusable(false);
        remove.setBorderPainted(false);
        remove.setContentAreaFilled(false);
        remove.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        remove.setMargin(new java.awt.Insets(0, 4, 0, 2));
        remove.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                removeAttachment(attachment);
            }
        });
        chip.add(remove, BorderLayout.EAST);
        return chip;
    }

    private static String ellipsize(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }
}
