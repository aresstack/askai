package com.aresstack.askai.research.document;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifacts;
import com.aresstack.askai.research.assembly.AssembledBook;
import com.aresstack.askai.research.assembly.BookExport;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.outline.Outline;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The Document tab: its sidebar pane is the book's table of contents with the writing state of every
 * section ({@link DocumentSectionsView}), the book itself shows in the workspace's MAIN area (where the chat
 * is) while the Document pane is active ({@link DocumentWorkView}).
 *
 * <p>Plan PR 26: the Book face shows the generated, read-only {@code document.md}; selecting a section in
 * the sidebar opens it in the editor ({@link SectionEditPanel}) with its local markers, revisions (view and
 * restore), Skip/Unskip and expected-revision saves that keep the local text on a conflict. Plan PR 29: the
 * writing job is started and stopped from the sidebar; it never changes the phase.
 *
 * <p>Until #41 introduces the real citation registry, {@code [n]} badges render as visibly
 * UNRESOLVED and do not navigate anywhere: a positional guess (n-th repository source) could
 * present the WRONG source as evidence, which is worse than no link. #41 wires the
 * {@link DocumentPageView.ReferenceResolver}/{@link DocumentPageView.ReferenceListener}
 * contract with real citation ids.</p>
 */
public final class ResearchDocumentViewContribution implements ArtifactViewContribution {

    private static final String EXPORT_FILE_NAME = "book.md";

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
        AgentSession session = context.getSession();
        final ResearchAgentSession research = session instanceof ResearchAgentSession
                ? (ResearchAgentSession) session : null;
        final DocumentSession port = research == null ? null : new SessionPort(research);

        final DocumentSectionsView sidebar = new DocumentSectionsView();
        final DocumentPageView page = new DocumentPageView();
        JScrollPane pageScroll = new JScrollPane(page);
        pageScroll.setBorder(BorderFactory.createEmptyBorder());
        pageScroll.getViewport().setOpaque(false);
        pageScroll.setOpaque(false);

        final DocumentWorkView[] workRef = new DocumentWorkView[1];
        final ManuscriptWorkspace manuscript = port == null ? null : port.manuscript();
        final SectionEditPanel editor = manuscript == null ? null
                : new SectionEditPanel(new SectionEditor(manuscript), port, new Consumer<String>() {
                    public void accept(String line) {
                        workRef[0].say(line);
                        sidebar.refresh(); // a save or a skip changes the section's state in the list
                    }
                });
        final DocumentWorkView work = new DocumentWorkView(pageScroll, editor);
        workRef[0] = work;

        // the Book face: the generated, read-only document.md (plan PR 26)
        final Runnable loadPage = new Runnable() {
            public void run() {
                if (store != null) {
                    page.setDocumentMarkdown(store.read(artifactId).getMarkdown());
                }
                work.setBookState(port == null ? "" : port.bookStatus());
            }
        };
        work.setCardListener(new IntConsumer() {
            public void accept(int card) {
                if (card == DocumentWorkView.CARD_BOOK) {
                    loadPage.run();
                } else {
                    editor.refreshSections(); // the local text of the open section is kept (RA-P008)
                    work.updateHead();
                }
            }
        });

        sidebar.setSession(port);
        sidebar.setSelectionListener(new Consumer<SectionEditor.Item>() {
            public void accept(SectionEditor.Item item) {
                work.openSection(item);
            }
        });
        if (store != null) {
            sidebar.setExportAction(new Runnable() {
                public void run() {
                    export(sidebar, store, artifactId);
                }
            });
        }

        if (research != null) {
            final UiExecutor uiExecutor = context.getUiExecutor();
            final Runnable repaint = new Runnable() {
                public void run() {
                    sidebar.refresh();
                    if (work.shownCard() == DocumentWorkView.CARD_BOOK) {
                        loadPage.run();
                    } else {
                        editor.refreshState();
                        work.updateHead();
                    }
                }
            };
            final Runnable refresh = new Runnable() {
                public void run() {
                    uiExecutor.execute(repaint);
                }
            };
            sidebar.addAncestorListener(new javax.swing.event.AncestorListener() {
                public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                    research.addStateListener(refresh);
                    repaint.run();
                }

                public void ancestorMoved(javax.swing.event.AncestorEvent event) {
                }

                public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                    research.removeStateListener(refresh);
                }
            });
            research.addStateListener(refresh);
        }
        loadPage.run();
        // the book in the main area, its table of contents in the sidebar
        context.showMainContent(work);
        return sidebar;
    }

    /** "Export the book": ask for a file, never replace one silently, say where it went. */
    private static void export(DocumentSectionsView sidebar, AgentArtifactStore store, String artifactId) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(EXPORT_FILE_NAME));
        if (chooser.showSaveDialog(sidebar) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = BookExport.resolve(chooser.getSelectedFile());
        if (target.exists() && JOptionPane.showConfirmDialog(sidebar,
                target.getName() + " already exists. Replace it?", "Export book",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            File written = BookExport.write(store.read(artifactId).getMarkdown(), chooser.getSelectedFile());
            sidebar.say("Exported to " + written.getName());
        } catch (IOException failed) {
            sidebar.say("Export failed: " + failed.getMessage());
        }
    }

    /** The research session behind the tab's port. */
    private static final class SessionPort implements DocumentSession {
        private final ResearchAgentSession research;

        SessionPort(ResearchAgentSession research) {
            this.research = research;
        }

        public Outline approvedOutline() {
            return research.approvedOutlineOrNull();
        }

        public ManuscriptWorkspace manuscript() {
            return research.manuscript();
        }

        public String writeRefusal() {
            return research.manuscriptWriteRefusal();
        }

        public boolean writingRunning() {
            return research.writingRunning();
        }

        public String writingStatus() {
            String line = research.writingStatus();
            return line == null ? "" : line;
        }

        public boolean hasStaleSections() {
            return !research.staleSectionTitles().isEmpty();
        }

        public String changeRequest() {
            String comment = research.documentChangeRequest();
            return comment == null ? "" : comment;
        }

        public String bookStatus() {
            AssembledBook book = research.assembledBook();
            return book == null ? "" : book.getReport().getStatus().label();
        }

        public String bookReport() {
            AssembledBook book = research.assembledBook();
            return book == null ? "" : book.getReport().toMarkdown();
        }

        public String writeRemaining() {
            return research.writeRemainingSections();
        }

        public void stopWriting() {
            research.stopWriting();
        }

        public String rewriteStale() {
            return research.rewriteStaleSections();
        }

        public String rewriteSection(String sectionId, String comment) {
            return research.rewriteSection(sectionId, comment);
        }

        public String rewriteChapter(String chapterId, String comment) {
            return research.rewriteChapter(chapterId, comment);
        }

        public String researchGap(String sectionId, String sectionTitle, String gap) {
            return research.researchGap(sectionId, sectionTitle, gap);
        }
    }
}
