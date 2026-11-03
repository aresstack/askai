package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.acp.ResearchBackendMode;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicForm;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;

/**
 * The browser's own switches at the top of the "Browser" tab: which browser, visible or headless, and the
 * development-only private-target override. They are stored through {@link ResearchRuntimeSettings} under the
 * long-standing keys and saved on every change (like the other runtime switches); new sessions use them.
 */
final class BrowserBasicsRows {

    private BrowserBasicsRows() {
    }

    static void addTo(ComicForm form, final WorkspaceStateStore store) {
        ResearchRuntimeSettings current = ResearchRuntimeDefaults.complete(ResearchRuntimeSettings.load(store));
        final JComboBox<String> channel = ComicControls.<String>comboBox(
                new String[]{ResearchRuntimeSettings.CAMOUFOX_CHANNEL, "chrome", "msedge"});
        channel.setSelectedItem(current.getBrowserChannel());
        channel.setToolTipText("The browser that searches and opens the hits (applies to new sessions)");
        final JCheckBox headless = ComicControls.checkBox("Run the browser headless", current.isHeadless());
        // Transparency does not depend on a visible browser window: the chat shows the visited sites.
        String headlessHint = new com.aresstack.askai.research.agent.ResearchPlaybook(
                com.aresstack.askai.research.agent.ResearchLanguage.fromCode(
                        ResearchRuntimeSettings.loadLanguage(store))).headlessHint();
        final JCheckBox allowPrivate = ComicControls.checkBox(
                "Allow private/loopback targets (development only)", current.isAllowPrivateNetworks());
        allowPrivate.setToolTipText("Lets the browser open localhost and private network addresses;"
                + " off (default) blocks them");
        Runnable persist = () -> {
            ResearchRuntimeSettings stored = ResearchRuntimeSettings.load(store);
            // Like the former Runtime "Save": the automatic (productive-when-possible) mode is written along.
            new ResearchRuntimeSettings(ResearchBackendMode.ACP, String.valueOf(channel.getSelectedItem()),
                    headless.isSelected(), allowPrivate.isSelected(), stored.getSelectedRerankerModel())
                    .save(store);
        };
        channel.addActionListener(e -> persist.run());
        headless.addActionListener(e -> persist.run());
        allowPrivate.addActionListener(e -> persist.run());
        form.row("Browser channel", channel);
        form.row("", headless);
        form.row("", ComicForm.hint(headlessHint));
        form.row("", allowPrivate);
    }
}
