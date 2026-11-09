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
        final JLabel report = new JLabel(" ");
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        AgentSession session = context.getSession();
        final ResearchAgentSession research = session instanceof ResearchAgentSession
                ? (ResearchAgentSession) session : null;
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
                com.aresstack.askai.research.assembly.AssembledBook book =
                        research == null ? null : research.assembledBook();
                report.setText(book == null ? " " : book.getReport().summary());
                report.setToolTipText(book == null ? null : "<html><pre>"
                        + book.getReport().toMarkdown().replace("&", "&amp;").replace("<", "&lt;")
                        + "</pre></html>");
            }
        };

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
        final JButton export = new ComicButton("Export book...");
        export.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (store == null) {
                    return;
                }
                javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
                chooser.setSelectedFile(new java.io.File("book.md"));
                if (chooser.showSaveDialog(export) != javax.swing.JFileChooser.APPROVE_OPTION) {
                    return;
                }
                java.io.File target = com.aresstack.askai.research.assembly.BookExport.resolve(
                        chooser.getSelectedFile());
                if (target.exists() && javax.swing.JOptionPane.showConfirmDialog(export,
                        target.getName() + " already exists. Replace it?", "Export book",
                        javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) {
                    return;
                }
                try {
                    java.io.File written = com.aresstack.askai.research.assembly.BookExport.write(
                            store.read(artifactId).getMarkdown(), chooser.getSelectedFile());
                    status.setText("Exported to " + written.getName());
                } catch (java.io.IOException failed) {
                    status.setText("Export failed: " + failed.getMessage());
                }
            }
        });
        toolbar.add(export);
        toolbar.add(report);
        toolbar.add(status);

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        root.add(toolbar, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        loadPage.run();
        // the document in the main area, the outline in the drawer
        context.showMainContent(root);
        return new com.aresstack.askai.research.agent.ResearchOutlineViewContribution().createView(context);
    }

}
