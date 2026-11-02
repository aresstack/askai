package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import javax.swing.SwingUtilities;

import static org.junit.Assert.assertTrue;

/**
 * One pass through every LIVE transcript path the research flow uses — user bubble, streamed
 * assistant Markdown, tool-activity bubble with bar/url markers, info line with terminal status.
 * A construction or update that throws on the EDT kills the LIVE rendering while persistence keeps
 * working, which surfaces as "appears only after a restart" — exactly the failure this test pins.
 */
public class BubbleTranscriptSmokeTest {

    @Test
    public void everyLiveRenderPathSurvivesOnePass() throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        final boolean[] retried = {false};
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                BubbleTranscriptPanel panel = new BubbleTranscriptPanel(
                        BubblePalette.windowsPhoneInspired());
                panel.setSize(500, 800);

                panel.appendUserMessage("hühner richtig halten").setHeaderTimestamp(
                        System.currentTimeMillis());

                panel.startAssistantMessage("Agent");
                panel.appendAssistantDelta("Die **Quellen** zeigen …");
                panel.finishAssistantMessage();

                BubbleTranscriptPanel.AgentActivityHandle activity =
                        panel.startAgentActivity("Websuche", "hühner richtig halten");
                panel.updateAgentActivity(activity, "Websuche",
                        "Quellen: 1/6 · Seiten: 2/13 besucht · Links: 83 gefunden "
                                + "[[bar:1/6]] [[url:https://example.org/huehner]]");
                panel.updateAgentActivity(activity, "Websuche",
                        "Quellen: 2/6 [[bar:2/6]] [[url:https://example.org/stall]]");
                panel.completeAgentActivity(activity, "6 Quellen");

                panel.appendInfo("info-1", "Websuche: hühner richtig halten");
                panel.markInfoStatus("info-1", true, null);
                panel.appendInfo("info-2", "Websuche: fehlgeschlagene suche");
                panel.markInfoStatus("info-2", false, new Runnable() {
                    public void run() {
                        retried[0] = true;
                    }
                });
                assertTrue("transcript rendered rows", panel.getComponentCount() > 0);
            }
        });
    }
}
