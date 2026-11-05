package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.comiccontrols.control.ResearchPillDropdown;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPainter;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The research PHASE SELECTOR — a dark pill in the FOOTER of the State pane (built there by
 * {@link ResearchStateViewContribution}; no longer registered as a toolbar contribution) — formerly beside the language
 * switch (maintenance corner: rarely touched in the normal flow), always showing its ≤8-character
 * short label while the popup carries the full phase titles. Dark "New Chat" accent
 * ({@link ResearchUiPalette#SECONDARY_SURFACE}), shared with the hamburger and the active ribbon
 * entry. It is deliberately NOT a second state
 * mechanism: the dropdown renders {@link ResearchStateSnapshot#getPhaseOrder()}, a selectable entry
 * exists exactly where {@link ResearchStateSnapshot#advanceCommandFor} names an allowed USER
 * command, and a click runs that command through {@link ResearchAgentSession#executeCommand} — the
 * same processor as the red tags, {@code /do}, the MCP {@code run_command} and the State tab's
 * phase clicks. Invalid switches stay impossible; the State artifact remains the detailed view.
 */
public final class ResearchPhaseToolbarContribution implements AgentToolbarContribution {

    /** Display names per phase id, in snapshot order ("Phase N: …" is prefixed at render time). */
    private static final Map<String, String> PHASE_TITLES;
    /**
     * The REDUCED labels for the unfolded-menu state — the map's KEY side: unique, at most 8
     * characters, lightly imperative (the plugin's own wording); the full title stays the VALUE
     * the popup shows.
     */
    private static final Map<String, String> PHASE_SHORT_TITLES;
    private static final int SHORT_TITLE_LIMIT = 8;

    static {
        // #43: exactly the four product phases, English UI names, snapshot order.
        Map<String, String> titles = new LinkedHashMap<String, String>();
        Map<String, String> shorts = new LinkedHashMap<String, String>();
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING,
                "Concept / Structure I");
        shorts.put(com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING, "Concept");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH,
                "Sources / Content I");
        shorts.put(com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH, "Sources");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.OUTLINE,
                "Outline / Structure II");
        shorts.put(com.aresstack.askai.research.state.oo.ResearchStateIds.OUTLINE, "Outline");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.DRAFT,
                "Document / Content II");
        shorts.put(com.aresstack.askai.research.state.oo.ResearchStateIds.DRAFT, "Document");
        PHASE_TITLES = titles;
        PHASE_SHORT_TITLES = shorts;
    }

    @Override
    public String getId() {
        return "research-phase-selector";
    }

    @Override
    public Placement getPlacement() {
        // Bottom right, beside the language pill: the phase is rarely touched in the normal flow
        // (only when something was forgotten) — navigation stays up top, maintenance lives here.
        return Placement.SIDEBAR_FOOTER;
    }

    @Override
    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    @Override
    public JComponent createComponent(final AgentToolbarContext context) {
        final ResearchAgentSession session = (ResearchAgentSession) context.getSession();
        final UiExecutor uiExecutor = context.getUiExecutor();

        final ResearchPillDropdown pill = new ResearchPillDropdown(
                ResearchUiMetrics.FOOTER_CONTROL_HEIGHT, ResearchUiMetrics.RADIUS_CONTROL,
                0, ResearchUiMetrics.FOOTER_PILL_PADDING_H,
                ResearchUiMetrics.FOOTER_PILL_PADDING_H);
        pill.setFont(ResearchUiTypography.regular(13f));
        // The DARK "New Chat" family (SECONDARY_SURFACE) — one near-black accent shared with the
        // hamburger and the active ribbon entry; hover/open lift it slightly.
        pill.setFills(ResearchUiPalette.SECONDARY_SURFACE, ResearchUiPalette.SECONDARY_HOVER,
                ThemeColor.mix(ResearchUiPalette.SECONDARY_HOVER, ThemeColor.of("comic.buttonPlate"),
                        0.06f));
        pill.setPillForeground(ResearchUiPalette.TEXT_PRIMARY);
        pill.setToolTipText("Research-Phase (Wechsel läuft über den regulären Workflow)");
        // ALWAYS the ≤8-character short label on the pill; the popup shows the full titles. The
        // old open-menu size flipping is gone — it was flaky and is simply not needed anymore.
        pill.setCompact(true);

        pill.setSelectionListener(new ResearchPillDropdown.SelectionListener() {
            public void itemSelected(int index) {
                dispatchPhaseClick(session, pill, index);
            }
        });

        final Runnable refresh = new Runnable() {
            public void run() {
                final ResearchStateSnapshot snapshot = session.currentResearchSnapshot();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        applySnapshot(pill, snapshot);
                    }
                });
            }
        };
        pill.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                session.addStateListener(refresh); // addIfAbsent — re-showing never doubles it
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                session.removeStateListener(refresh);
            }
        });
        session.addStateListener(refresh);
        refresh.run(); // initial paint
        return pill; // glyph, text and chevron are ONE control now — no separate icon beside it
    }

    /** Rebuild the dropdown rows from the snapshot: reachable phases enabled, everything else quiet. */
    private static void applySnapshot(ResearchPillDropdown pill, ResearchStateSnapshot snapshot) {
        List<String> order = snapshot.getPhaseOrder();
        List<ResearchPillDropdown.Item> items = new ArrayList<ResearchPillDropdown.Item>();
        int selected = 0;
        for (int index = 0; index < order.size(); index++) {
            String phaseId = order.get(index);
            if (phaseId.equals(snapshot.getCurrentPhaseId())) {
                selected = index;
            }
            ResearchCommandType command = snapshot.advanceCommandFor(phaseId);
            boolean reachable = command != null
                    && ResearchSemanticCommands.semanticNameFor(command) != null;
            items.add(new ResearchPillDropdown.Item(phaseLabel(index, phaseId),
                    shortLabel(phaseId), null, reachable,
                    reachable ? ResearchStateView.label(command) : null));
        }
        pill.setItems(items);
        pill.setSelectedIndex(selected);
    }

    /** Run the phase click through the ONE semantic command processor; rejections stay visible. */
    private static void dispatchPhaseClick(ResearchAgentSession session, ResearchPillDropdown pill,
                                           int index) {
        ResearchStateSnapshot snapshot = session.currentResearchSnapshot();
        List<String> order = snapshot.getPhaseOrder();
        if (index < 0 || index >= order.size()) {
            return;
        }
        ResearchCommandType command = snapshot.advanceCommandFor(order.get(index));
        String semantic = command == null ? null : ResearchSemanticCommands.semanticNameFor(command);
        if (semantic == null) {
            return; // the domain withdrew the transition since the popup opened
        }
        String outcome = session.executeCommand(semantic, "");
        if (outcome != null && !outcome.startsWith("handled")) {
            JOptionPane.showMessageDialog(pill,
                    outcome.replaceFirst("^rejected:\\s*", ""),
                    "Phase change refused", JOptionPane.WARNING_MESSAGE);
        }
        // Accepted commands need no handling: the session fires its state change and the ordinary
        // listener re-renders the pill from the new domain state.
    }

    private static String phaseLabel(int index, String phaseId) {
        String title = PHASE_TITLES.get(phaseId);
        return "Phase " + (index + 1) + ": " + (title == null ? phaseId : title);
    }

    /** The ≤8-character key shown while the tab menu is unfolded (hard-capped defensively). */
    private static String shortLabel(String phaseId) {
        String label = PHASE_SHORT_TITLES.get(phaseId);
        if (label == null) {
            label = phaseId;
        }
        return label.length() <= SHORT_TITLE_LIMIT
                ? label : label.substring(0, SHORT_TITLE_LIMIT);
    }

    /**
     * The lab-flask (Erlenmeyer) glyph — monochrome Java2D, no emoji, painted with the OWNING
     * component's foreground so it follows every button state (ink at rest, white when the burger
     * is latched dark). 16×18. Public: it BRANDS the workspace hamburger while the research agent
     * is active ({@code ResearchAgentPluginExtension.getMenuIcon()}).
     */
    public static final class FlaskIcon implements javax.swing.Icon {

        public int getIconWidth() {
            return 16;
        }

        public int getIconHeight() {
            return 18;
        }

        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                g2.setColor(component != null && component.getForeground() != null
                        ? component.getForeground() : ResearchUiPalette.TEXT_PRIMARY);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // Neck (two verticals), shoulders flaring out, flat bottom — the classic flask.
                Path2D.Float flask = new Path2D.Float();
                flask.moveTo(x + 6f, y + 1.5f);
                flask.lineTo(x + 6f, y + 6.5f);
                flask.lineTo(x + 2f, y + 15.5f);
                flask.lineTo(x + 14f, y + 15.5f);
                flask.lineTo(x + 10f, y + 6.5f);
                flask.lineTo(x + 10f, y + 1.5f);
                g2.draw(flask);
                g2.drawLine(x + 4, y + 1, x + 12, y + 1); // the rim
            } finally {
                g2.dispose();
            }
        }
    }
}
