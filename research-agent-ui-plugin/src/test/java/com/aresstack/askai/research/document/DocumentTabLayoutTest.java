package com.aresstack.askai.research.document;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.agent.ResearchArtifacts;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Document tab: the book's table of contents is the sidebar pane, the book itself goes to the main area. */
public class DocumentTabLayoutTest {

    @Test
    public void theSectionsAreTheSidebarAndTheBookShowsInTheMainArea() {
        final List<JComponent> mainContent = new ArrayList<JComponent>();
        final AgentArtifact document = artifact("document");
        ArtifactViewContext context = new ArtifactViewContext() {
            public AgentArtifact getArtifact() {
                return document;
            }

            public AgentSession getSession() {
                return proxy(AgentSession.class, null);
            }

            public UiExecutor getUiExecutor() {
                return proxy(UiExecutor.class, null);
            }

            public ThemeService getThemeService() {
                return null;
            }

            public MarkdownViewFactory getMarkdownViewFactory() {
                return options -> proxy(MarkdownView.class, new JLabel("outline"));
            }

            public AgentArtifactStore getArtifactStore() {
                return null;
            }

            @Override
            public void showMainContent(JComponent content) {
                mainContent.add(content);
            }
        };
        JComponent sidebar = new ResearchDocumentViewContribution().createView(context);
        assertTrue("the sidebar pane is the table of contents", sidebar instanceof DocumentSectionsView);
        assertEquals("the document is the main-area content", 1, mainContent.size());
        assertTrue(mainContent.get(0) instanceof DocumentWorkView);
        assertTrue("the page canvas lives in the main area", contains(mainContent.get(0), DocumentPageView.class));
        assertFalse("the list is not in the main area", contains(mainContent.get(0), DocumentSectionsView.class));
        assertFalse("the pages are not in the sidebar", contains(sidebar, DocumentPageView.class));
    }

    @Test
    public void outlineHasNoTabOfItsOwnAnymore() {
        for (AgentArtifact artifact : ResearchArtifacts.all()) {
            assertFalse(ResearchArtifacts.TYPE_OUTLINE.equals(artifact.getArtifactTypeId()));
        }
    }

    private static AgentArtifact artifact(String id) {
        for (AgentArtifact artifact : ResearchArtifacts.all()) {
            if (id.equals(artifact.getId())) {
                return artifact;
            }
        }
        throw new AssertionError(id);
    }

    private static boolean contains(java.awt.Container root, Class<?> type) {
        if (type.isInstance(root)) {
            return true;
        }
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child) || child instanceof java.awt.Container && contains((java.awt.Container) child, type)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, final JComponent component) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            if ("getComponent".equals(method.getName())) {
                return component;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType() == long.class || method.getReturnType() == int.class) {
                return 0;
            }
            if ("toString".equals(method.getName())) {
                return type.getSimpleName();
            }
            return null;
        });
    }
}
