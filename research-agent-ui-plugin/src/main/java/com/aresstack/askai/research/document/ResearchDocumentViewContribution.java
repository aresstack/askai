package com.aresstack.askai.research.document;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifacts;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;

/**
 * The Document tab (Outline + Document in one): its DRAWER pane is the outline
 * ({@link com.aresstack.askai.research.agent.ResearchOutlineViewContribution} — later also the navigation
 * inside the document), the document itself shows in the workspace's MAIN area (where the chat is) while
 * the Document pane is active.
 *
 * <p>Plan PR 26: the page face shows the generated, read-only {@code document.md}; Edit opens one
 * section of the manuscript at a time ({@link SectionEditPanel}) with its local markers, revisions
 * (view and restore), Skip/Unskip and expected-revision saves that keep the local text on a conflict.
 *
 * <p>Until #41 introduces the real citation registry, {@code [n]} badges render as visibly
 * UNRESOLVED and do not navigate anywhere: a positional guess (n-th repository source) could
 * present the WRONG source as evidence, which is worse than no link. #41 wires the
 * {@link DocumentPageView.ReferenceResolver}/{@link DocumentPageView.ReferenceListener}
 * contract with real citation ids.</p>
 */
public final class ResearchDocumentViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_DOCUMENT;
    }

    @Override
    public String getDisplayName() {
        return "Document";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final AgentArtifactStore store = context.getArtifactStore();
        final String artifactId = context.getArtifact().getId();
        final DocumentPageView page = new DocumentPageView();
        final JLabel status = new JLabel(" ");
        final JPanel center = new JPanel(new CardLayout());
        JScrollPane pageScroll = new JScrollPane(page);
        pageScroll.setBorder(BorderFactory.createEmptyBorder());
        pageScroll.getViewport().setOpaque(false);
        pageScroll.setOpaque(false);
        center.setOpaque(false);
        center.add(pageScroll, "page");

        // the page face: the generated, read-only document.md (plan PR 26)
        final Runnable loadPage = new Runnable() {
            public void run() {
                if (store == null) {
                    status.setText("No artifact store available.");
                    return;
                }
                ArtifactContent content = store.read(artifactId);
                page.setDocumentMarkdown(content.getMarkdown());
            }
        };

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        AgentSession session = context.getSession();
        final ResearchAgentSession research = session instanceof ResearchAgentSession
                ? (ResearchAgentSession) session : null;
        final ManuscriptWorkspace manuscript = research == null ? null : research.manuscript();
        final boolean[] editing = {false};
        if (manuscript != null) {
            final SectionEditor editor = new SectionEditor(manuscript);
            final SectionEditPanel panel = new SectionEditPanel(editor, research, status);
            center.add(panel, "edit");
            final JButton toggle = new ComicButton("Edit");
            toggle.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    editing[0] = !editing[0];
                    if (editing[0]) {
                        panel.refreshSections(); // the local text of the open section is kept (RA-P008)
                    } else {
                        loadPage.run();
                    }
                    ((CardLayout) center.getLayout()).show(center, editing[0] ? "edit" : "page");
                    toggle.setText(editing[0] ? "Page" : "Edit");
                }
            });
            toolbar.add(toggle);
            // plan PR 29: the writing job (Document tab); the job never changes the phase
            final JButton writeRemaining = new ComicButton("Write remaining sections");
            final JButton stopWriting = new ComicButton("Stop writing");
            final JButton rewriteStale = new ComicButton("Rewrite stale sections");
            writeRemaining.addActionListener(e -> {
                String refusal = research.writeRemainingSections();
                status.setText(refusal == null ? "Writing..." : refusal);
            });
            stopWriting.addActionListener(e -> research.stopWriting());
            rewriteStale.addActionListener(e -> {
                String refusal = research.rewriteStaleSections();
                status.setText(refusal == null ? "Rewriting stale sections..." : refusal);
            });
            toolbar.add(writeRemaining);
            toolbar.add(stopWriting);
            toolbar.add(rewriteStale);
            final Runnable writingState = new Runnable() {
                public void run() {
                    boolean running = research.writingRunning();
                    writeRemaining.setEnabled(!running);
                    rewriteStale.setEnabled(!running && !research.staleSectionTitles().isEmpty());
                    stopWriting.setEnabled(running);
                    String line = research.writingStatus();
                    if (line != null && !line.isEmpty()) {
                        status.setText(line);
                    }
                }
            };
            research.addStateListener(writingState);
            writingState.run();
            research.addStateListener(new Runnable() {
                public void run() {
                    if (editing[0]) {
                        panel.refreshState();
                    } else {
                        loadPage.run();
                    }
                }
            });
        } else if (research != null) {
            research.addStateListener(loadPage);
        }
        toolbar.add(status);
        // plan PR 29: the latest "Request changes" comment; "Rewrite section" in Edit acts on it
        final JLabel changeRequest = new JLabel(" ");
        if (research != null) {
            final Runnable showRequest = new Runnable() {
                public void run() {
                    String comment = research.documentChangeRequest();
                    changeRequest.setText(comment == null || comment.isEmpty() ? " "
                            : "Requested changes: " + comment + " (use Edit > Rewrite section)");
                }
            };
            research.addStateListener(showRequest);
            showRequest.run();
        }

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(toolbar, BorderLayout.NORTH);
        north.add(changeRequest, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        loadPage.run();
        // the document in the main area, the outline in the drawer
        context.showMainContent(root);
        return new com.aresstack.askai.research.agent.ResearchOutlineViewContribution().createView(context);
    }

}
