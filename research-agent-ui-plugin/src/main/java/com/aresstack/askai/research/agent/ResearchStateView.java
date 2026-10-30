package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.backend.ResearchCommandDispatchResult;
import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.control.ComicSectionPanel;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The comic State tab: a phase TIMELINE of {@link ComicSectionPanel} plates (outer state object =
 * the phase plate; the ACTIVE phase nests a second, filled plate for its inner {@link
 * com.aresstack.askai.research.state.oo.PhaseState} — the visualization mirrors the two nested
 * state objects). The timeline IS the navigation: a phase an allowed USER command leads into is
 * CLICKABLE (hand cursor, ▶ marker, hover pop) and a click dispatches exactly that command —
 * forward (approve/submit/continue) and backward (request changes) alike, purely domain-driven
 * via {@link ResearchStateSnapshot#advanceCommandFor}. There is deliberately NO extra command
 * button bar: the domain's interruption machinery (pause/block/fail/…) is not user furniture,
 * and phase advancement already lives on the plates.
 *
 * <p>The view still holds NO transition table: clickability comes from the domain graph through
 * the snapshot, the user vocabulary from {@link ResearchSemanticCommands}, and a click only
 * forwards the {@link ResearchCommandType} to the injected {@link CommandListener} (the session's
 * semantic command processor) — never to the state machine directly. Rejections surface as an
 * ink-red feedback line; accepted transitions re-render through the ordinary snapshot listener.</p>
 */
public final class ResearchStateView extends JPanel {

    /** The seam to the session's command processor; returns the structured outcome for feedback. */
    public interface CommandListener {
        ResearchCommandDispatchResult commandClicked(ResearchCommandType command);
    }

    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final JPanel timeline = new JPanel();
    private final JLabel feedback = new JLabel(" ");
    private final Map<String, ResearchCommandType> clickablePhases =
            new LinkedHashMap<String, ResearchCommandType>();

    private ResearchStateSnapshot snapshot;
    private List<ResearchTask> tasks = Collections.emptyList();
    private CommandListener commandListener;

    public ResearchStateView() {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        timeline.setLayout(new BoxLayout(timeline, BoxLayout.Y_AXIS));
        timeline.setOpaque(false);
        JPanel timelineNorth = new JPanel(new BorderLayout());
        timelineNorth.setOpaque(false);
        timelineNorth.add(timeline, BorderLayout.NORTH); // plates keep their natural height
        JScrollPane scroll = new ComicScrollPane(timelineNorth,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        feedback.setForeground(palette.getAccentRed());
        feedback.setFont(feedback.getFont().deriveFont(Font.PLAIN, 11f));
        add(feedback, BorderLayout.SOUTH);

        rebuild();
    }

    /** Wire the session's command processor; without a listener the tab stays read-only. */
    public void setCommandListener(CommandListener listener) {
        this.commandListener = listener;
        rebuild();
    }

    /** Render a snapshot. Must be called on the EDT. */
    public void setSnapshot(ResearchStateSnapshot snapshot) {
        this.snapshot = snapshot;
        feedback.setText(" "); // a new state invalidates old rejection feedback
        rebuild();
    }

    /**
     * Render the persisted work packages of the run (ADR 0001, section 4.4) below the timeline. Read-only:
     * the list shows state, role and retries; it offers no control. Must be called on the EDT.
     */
    public void setTasks(List<ResearchTask> tasks) {
        this.tasks = tasks == null ? Collections.<ResearchTask>emptyList()
                : Collections.unmodifiableList(new ArrayList<ResearchTask>(tasks));
        rebuild();
    }

    // ------------------------------------------------------------------ timeline

    private void rebuild() {
        timeline.removeAll();
        clickablePhases.clear();
        if (snapshot == null) {
            JLabel none = new JLabel("No active research session.");
            none.setEnabled(false);
            none.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            timeline.add(none);
        } else {
            List<String> completed = snapshot.getCompletedPhaseIds();
            for (String phaseId : snapshot.getPhaseOrder()) {
                timeline.add(buildPhaseRow(phaseId, completed));
                timeline.add(javax.swing.Box.createVerticalStrut(6));
            }
            JLabel revision = new JLabel("revision " + snapshot.getRevision());
            revision.setEnabled(false);
            revision.setFont(revision.getFont().deriveFont(Font.PLAIN, 11f));
            timeline.add(revision);
        }
        if (!tasks.isEmpty()) {
            timeline.add(javax.swing.Box.createVerticalStrut(6));
            timeline.add(buildTaskList());
        }
        timeline.revalidate();
        timeline.repaint();
    }

    /** One phase plate; the active one nests the inner state's own (filled) plate. */
    private JComponent buildPhaseRow(String phaseId, List<String> completed) {
        ComicSectionPanel row = new ComicSectionPanel(palette);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));

        boolean active = phaseId.equals(snapshot.getCurrentPhaseId());
        JLabel title = new JLabel(phaseDisplayName(phaseId));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setForeground(active ? palette.getInk() : ThemeColor.derive("ink.brighter",
                () -> palette.getInk().brighter()));
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.WEST);
        titleRow.setAlignmentX(LEFT_ALIGNMENT);
        row.add(titleRow);

        if (active) {
            row.setAccentStripe(problemAccent() != null ? problemAccent() : palette.getAccentYellow());
            row.add(javax.swing.Box.createVerticalStrut(4));
            row.add(buildInnerStatePlate());
        } else if (completed.contains(phaseId)) {
            row.setAccentStripe(palette.getAgentPetrol());
            row.add(quietLine("completed"));
        } else {
            row.setAccentStripe(ThemeColor.of("research.phasePendingStripe"));
            row.add(quietLine("pending"));
        }
        makeClickableWhenTheDomainAllowsIt(row, titleRow, phaseId);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /**
     * The click affordance: only when an allowed command from the USER vocabulary leads into this
     * phase does the plate become a control — hand cursor, a ▶ marker, hover pop, and the action
     * spelled out in the tooltip. Everything else stays a plain display plate.
     */
    private void makeClickableWhenTheDomainAllowsIt(final ComicSectionPanel row, JPanel titleRow,
                                                    final String phaseId) {
        final ResearchCommandType command = snapshot.advanceCommandFor(phaseId);
        if (command == null || commandListener == null
                || ResearchSemanticCommands.semanticNameFor(command) == null) {
            return;
        }
        clickablePhases.put(phaseId, command);
        JLabel marker = new JLabel("▶");
        marker.setForeground(palette.getAccentOrange());
        marker.setFont(marker.getFont().deriveFont(Font.BOLD, 12f));
        titleRow.add(marker, BorderLayout.EAST);
        row.setToolTipText(label(command));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        final Color resting = row.getPlateFill();
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                row.setPlateFill(ThemeColor.average(palette.getAccentYellow(),
                        ThemeColor.of("comic.buttonPlate")));
            }

            @Override
            public void mouseExited(MouseEvent event) {
                row.setPlateFill(resting);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                dispatch(command);
            }
        });
    }

    /** The minimal task list: one quiet line per work package, in submission order. */
    private JComponent buildTaskList() {
        ComicSectionPanel plate = new ComicSectionPanel(palette);
        plate.setName("research.state.tasks");
        plate.setLayout(new BoxLayout(plate, BoxLayout.Y_AXIS));
        plate.setAccentStripe(palette.getAgentPetrol());
        JLabel title = new JLabel("TASKS");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        plate.add(title);
        for (ResearchTask task : tasks) {
            JComponent line = quietLine(taskLine(task));
            line.setAlignmentX(LEFT_ALIGNMENT);
            plate.add(line);
        }
        plate.setAlignmentX(LEFT_ALIGNMENT);
        plate.setMaximumSize(new Dimension(Integer.MAX_VALUE, plate.getPreferredSize().height));
        return plate;
    }

    /** {@code "t-2 · source-verifier · working · retry 1/2 · after t-1"}. */
    static String taskLine(ResearchTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append(task.getTaskId()).append(" · ").append(task.getRole())
                .append(" · ").append(task.getState().wireName());
        if (task.getRetryCount() > 0) {
            sb.append(" · retry ").append(task.getRetryCount()).append('/').append(task.getRetryLimit());
        }
        if (!task.getDependsOn().isEmpty()) {
            sb.append(" · after ");
            for (int i = 0; i < task.getDependsOn().size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(task.getDependsOn().get(i));
            }
        }
        return sb.toString();
    }

    /** The nested plate for the INNER state object — this one is allowed to pop. */
    private JComponent buildInnerStatePlate() {
        ComicSectionPanel inner = new ComicSectionPanel(palette);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setPlateFill(innerStateFill());

        JLabel state = new JLabel(upper(snapshot.getCurrentStateId()));
        state.setFont(state.getFont().deriveFont(Font.BOLD, 12f));
        state.setForeground(innerStateForeground());
        inner.add(state);
        if (snapshot.getContinuationStateId() != null) {
            inner.add(detailLine("continuation: " + upper(snapshot.getContinuationStateId())));
        }
        if (snapshot.getPendingApprovalId() != null) {
            inner.add(detailLine("approval: " + snapshot.getPendingApprovalId()));
        }
        if (!snapshot.getProblem().isEmpty()) {
            inner.add(detailLine("reason: " + snapshot.getProblem()));
        }

        JPanel indent = new JPanel(new BorderLayout());
        indent.setOpaque(false);
        indent.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0)); // the nesting is visible
        indent.add(inner, BorderLayout.CENTER);
        indent.setAlignmentX(LEFT_ALIGNMENT);
        return indent;
    }

    private Color innerStateFill() {
        String stateId = snapshot.getCurrentStateId();
        if (ResearchStateIds.COMPLETED.equals(stateId)) {
            return palette.getAgentPetrol();
        }
        if (problemAccent() != null) {
            return problemAccent();
        }
        return palette.getAccentYellow(); // running / waiting / approval: the action accent
    }

    private Color innerStateForeground() {
        // Petrol and red fills need light text; the yellow plate keeps ink.
        return innerStateFill() == palette.getAccentYellow() ? palette.getInk()
                : ThemeColor.of("bubble.assistantForeground");
    }

    /** Red for the states that mean trouble/stop, else {@code null}. */
    private Color problemAccent() {
        String stateId = snapshot.getCurrentStateId();
        boolean trouble = ResearchStateIds.FAILED.equals(stateId)
                || ResearchStateIds.BLOCKED.equals(stateId)
                || ResearchStateIds.CANCELLED.equals(stateId);
        return trouble ? palette.getAccentRed() : null;
    }

    private void dispatch(ResearchCommandType type) {
        ResearchCommandDispatchResult result = commandListener.commandClicked(type);
        if (result != null && !result.isAccepted()) {
            String detail = result.getDetail();
            feedback.setText(detail == null || detail.isEmpty()
                    ? result.getStatus().toString() : detail);
        }
        // Accepted commands need no handling here: the session fires its state change and the
        // ordinary snapshot listener re-renders timeline + plates from the new domain state.
    }

    /** "REQUEST_EVIDENCE_REVIEW" → "Request evidence review". */
    static String label(ResearchCommandType type) {
        String words = type.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    // ------------------------------------------------------------------ text projection

    /**
     * The plain-text projection of the current snapshot — the tab's accessible summary and the
     * stable seam for tests (the Swing composition may evolve freely underneath it).
     */
    public String renderedText() {
        String text = render(snapshot);
        if (tasks.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text).append("\ntasks:\n");
        for (ResearchTask task : tasks) {
            sb.append("  ").append(taskLine(task)).append('\n');
        }
        return sb.toString();
    }

    static String render(ResearchStateSnapshot s) {
        if (s == null) {
            return "No active research session.";
        }
        StringBuilder sb = new StringBuilder();
        List<String> completed = s.getCompletedPhaseIds();
        for (String phaseId : s.getPhaseOrder()) {
            sb.append(phaseDisplayName(phaseId)).append('\n');
            if (phaseId.equals(s.getCurrentPhaseId())) {
                sb.append("  active");
                if (s.isTerminal()) {
                    sb.append(" · ").append(upper(s.getCurrentStateId()));
                }
                sb.append('\n');
                sb.append("  └── ").append(upper(s.getCurrentStateId())).append('\n');
                if (s.getContinuationStateId() != null) {
                    sb.append("      continuation: ").append(upper(s.getContinuationStateId())).append('\n');
                }
                if (s.getPendingApprovalId() != null) {
                    sb.append("      approval: ").append(s.getPendingApprovalId()).append('\n');
                }
                if (!s.getProblem().isEmpty()) {
                    sb.append("      reason: ").append(s.getProblem()).append('\n');
                }
            } else if (completed.contains(phaseId)) {
                sb.append("  completed\n");
            } else {
                sb.append("  pending\n");
            }
            sb.append('\n');
        }
        sb.append("revision: ").append(s.getRevision()).append('\n');
        sb.append("allowed: ").append(commandNames(s)).append('\n');
        return sb.toString();
    }

    private static String commandNames(ResearchStateSnapshot s) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ResearchCommandType type : s.getAllowedCommands()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(type);
            first = false;
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private JComponent quietLine(String text) {
        JLabel label = new JLabel(text);
        label.setEnabled(false);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    private JComponent detailLine(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(innerStateForeground());
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    private static String upper(String id) {
        return id == null ? "" : id.toUpperCase();
    }

    /**
     * #43: the timeline shows the PRODUCT names — the technical compat ids (scoping/research/
     * draft) never surface as the product model. Inner run states stay technical vocabulary.
     */
    private static String phaseDisplayName(String phaseId) {
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(phaseId)) {
            return "CONCEPT";
        }
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH.equals(phaseId)) {
            return "SOURCES";
        }
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.DRAFT.equals(phaseId)) {
            return "DOCUMENT";
        }
        return upper(phaseId);
    }

    // ------------------------------------------------------------------ test accessors

    Map<String, ResearchCommandType> clickablePhasesForTest() {
        return new LinkedHashMap<String, ResearchCommandType>(clickablePhases);
    }

    void clickPhaseForTest(String phaseId) {
        ResearchCommandType command = clickablePhases.get(phaseId);
        if (command != null) {
            dispatch(command);
        }
    }

    String feedbackTextForTest() {
        return feedback.getText();
    }
}
