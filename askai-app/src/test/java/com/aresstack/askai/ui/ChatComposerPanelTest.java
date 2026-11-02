package com.aresstack.askai.ui;

import com.aresstack.askai.vision.ChatDraft;
import com.aresstack.askai.vision.ImageAttachment;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatComposerPanelTest {

    @Test
    public void switchesThePrimaryActionBetweenSendAndStop() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ActionCounter actions = new ActionCounter();
                ChatComposerPanel composer = new ChatComposerPanel(actions);

                assertFalse(composer.isSendEnabled());
                composer.setMessage("Hello");
                assertTrue(composer.isSendEnabled());

                findButton(composer, "Send").doClick();
                assertEquals(1, actions.count("send"));

                composer.setChatBusy(true);
                assertFalse(findButton(composer, "Send").isVisible());
                assertTrue(findButton(composer, "Stop").isVisible());

                findButton(composer, "Stop").doClick();
                assertEquals(1, actions.count("stop"));
            }
        });
    }

    @Test
    public void rendersRecordingAsAnAmberContextualState() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ActionCounter actions = new ActionCounter();
                ChatComposerPanel composer = new ChatComposerPanel(actions);
                composer.setMessage("Keep this draft");

                composer.setDictationView(new ChatComposerPanel.DictationView(
                        "Stop", true, true, false, true,
                        false, false, false, false, true));

                assertFalse(composer.isSendEnabled());
                JButton record = findButton(composer, "Record or stop dictation");
                JButton discard = findButton(composer, "Discard or cancel dictation");
                // The record button is icon-only now; the action is shown via the tooltip.
                assertEquals("Stop", record.getToolTipText());
                assertTrue(discard.isVisible());

                record.doClick();
                discard.doClick();
                assertEquals(1, actions.count("record"));
                assertEquals(1, actions.count("discard"));
            }
        });
    }

    @Test
    public void exposesAModeSelectorNextToTheModel() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ActionCounter actions = new ActionCounter();
                ChatComposerPanel composer = new ChatComposerPanel(actions);

                JButton mode = findButton(composer, "Choose the interaction mode");
                assertEquals("Yapping", mode.getText());

                mode.doClick();
                assertEquals(1, actions.count("mode"));

                composer.setModeName("Questing");
                assertEquals("Questing", mode.getText());
                composer.setModeName("");
                assertEquals("Yapping", mode.getText());
            }
        });
    }

    @Test
    public void reasoningSelectorIsGreyedUntilThinkingIsSupported() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ActionCounter actions = new ActionCounter();
                ChatComposerPanel composer = new ChatComposerPanel(actions);

                JButton reasoning = findButton(composer, "Thinking effort (only for models that support it)");
                assertEquals("Think: Off", reasoning.getText());
                assertFalse("greyed out until a thinking-capable model is selected", reasoning.isEnabled());

                composer.setReasoningEnabled(true);
                assertTrue(reasoning.isEnabled());
                reasoning.doClick();
                assertEquals(1, actions.count("reasoning"));

                composer.setReasoningName("Think: High");
                assertEquals("Think: High", reasoning.getText());
            }
        });
    }

    @Test
    public void imageAttachmentsRouteEnableSendSurviveModelSwitchAndClear() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ActionCounter actions = new ActionCounter();
                ChatComposerPanel composer = new ChatComposerPanel(actions);

                findButton(composer, "Attach images").doClick();
                assertEquals(1, actions.count("attach"));

                assertFalse("empty composer cannot send", composer.isSendEnabled());
                ImageAttachment a = ImageAttachment.of(new File("a.png"));
                ImageAttachment b = ImageAttachment.of(new File("b.png"));
                composer.addAttachments(Arrays.asList(a, b));

                assertTrue("an image-only draft is sendable", composer.isSendEnabled());
                assertEquals(2, composer.getAttachments().size());

                // Attachments must survive a model switch (they are not silently dropped).
                composer.setModelName("llava");
                assertEquals(2, composer.getAttachments().size());

                // Duplicates by file are ignored.
                composer.addAttachments(Collections.singletonList(a));
                assertEquals(2, composer.getAttachments().size());

                ChatDraft draft = composer.getDraft();
                assertEquals(2, draft.getAttachments().size());
                assertFalse(draft.hasText());
                assertTrue(draft.hasAttachments());

                composer.clearDraft();
                assertEquals(0, composer.getAttachments().size());
                assertFalse("cleared draft cannot send", composer.isSendEnabled());
            }
        });
    }

    private static JButton findButton(Container root, String accessibleName) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton) {
                JComponent swingComponent = (JComponent) component;
                if (accessibleName.equals(swingComponent.getAccessibleContext().getAccessibleName())) {
                    return (JButton) component;
                }
            }
            if (component instanceof Container) {
                JButton match = findButtonOrNull((Container) component, accessibleName);
                if (match != null) {
                    return match;
                }
            }
        }
        throw new IllegalStateException("Missing button: " + accessibleName);
    }

    private static JButton findButtonOrNull(Container root, String accessibleName) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton) {
                JComponent swingComponent = (JComponent) component;
                if (accessibleName.equals(swingComponent.getAccessibleContext().getAccessibleName())) {
                    return (JButton) component;
                }
            }
            if (component instanceof Container) {
                JButton match = findButtonOrNull((Container) component, accessibleName);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static final class ActionCounter implements ChatComposerPanel.Actions {
        private final Map<String, Integer> counts = new HashMap<String, Integer>();

        public void selectModel() {
            increment("model");
        }

        public void selectMode() {
            increment("mode");
        }

        public void selectReasoning() {
            increment("reasoning");
        }

        public void toggleNotificationsMute() {
            increment("toggleNotificationsMute");
        }

        public void send() {
            increment("send");
        }

        public void stop() {
            increment("stop");
        }

        public void toggleRecording() {
            increment("record");
        }

        public void discardDictation() {
            increment("discard");
        }

        public void retryTranscription() {
            increment("retry");
        }

        public void saveRecording() {
            increment("save");
        }

        public void playRecording() {
            increment("play");
        }

        public void installAudioModel() {
            increment("install");
        }

        public void transcribeAudioFile() {
            increment("file");
        }

        public void attachImages() {
            increment("attach");
        }

        private void increment(String action) {
            counts.put(action, count(action) + 1);
        }

        private int count(String action) {
            Integer value = counts.get(action);
            return value == null ? 0 : value.intValue();
        }
    }
}
