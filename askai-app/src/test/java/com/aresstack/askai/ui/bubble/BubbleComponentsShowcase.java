package com.aresstack.askai.ui.bubble;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/** Launch the component showcase directly from the IDE. */
public final class BubbleComponentsShowcase {

    private BubbleComponentsShowcase() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                showFrame();
            }
        });
    }

    private static void showFrame() {
        JFrame frame = new JFrame("AskAI Bubble Components");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        final BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
        transcript.appendUserMessage("Prüfe bitte, ob das Modell diese Aufgabe wirklich unterstützt.");
        transcript.startAssistantMessage("AskAI");
        transcript.appendAssistantDelta("Ich prüfe zuerst die offizielle Quelle.");
        transcript.finishAssistantMessage();
        final BubbleTranscriptPanel.AgentActivityHandle activity = transcript.startAgentActivity(
                "Herstellerseite öffnen",
                "Die offizielle Produktseite soll die technischen Angaben verifizieren.");

        final BubbleTranscriptPanel.ThinkingHandle thinking = transcript.startAssistantThinking("AskAI");
        transcript.appendAssistantThinkingDelta(thinking, "Ich vergleiche die Angaben mit der Quelle …");

        frame.add(transcript, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(900, 620));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        Timer completionTimer = new Timer(4200, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                transcript.completeAssistantThinking(thinking, "Quelle geprüft");
                transcript.completeAgentActivity(activity, "Produktdaten verifiziert");
            }
        });
        completionTimer.setRepeats(false);
        completionTimer.start();
    }
}
