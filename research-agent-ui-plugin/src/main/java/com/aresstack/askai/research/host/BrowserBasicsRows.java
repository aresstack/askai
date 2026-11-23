package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agentwire.ResearchBackendMode;
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
                new String[]{ResearchRuntimeSettings.CAMOUFOX_CHANNEL, "firefox", "chrome", "msedge"});
        channel.setSelectedItem(current.getBrowserChannel());
        channel.setToolTipText("The browser that searches and opens the hits (applies to new sessions)");
        final JCheckBox headless = ComicControls.checkBox("Run the research browser headless", current.isHeadless());
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
        final BrowserLocationControl location = new BrowserLocationControl(store);
        location.setChannel(String.valueOf(channel.getSelectedItem()));
        channel.addActionListener(e -> {
            persist.run();
            location.setChannel(String.valueOf(channel.getSelectedItem()));
        });
        headless.addActionListener(e -> persist.run());
        allowPrivate.addActionListener(e -> persist.run());
        form.row("Browser channel", channel);
        form.row("Browser location", location.field(), location.browseButton(), location.downloadButton());
        form.row("", location.status());
        form.row("", location.progress());
        form.row("", headless);
        form.row("", ComicForm.hint(headlessHint + " Off (default): you see the pages the research opens."));
        form.row("", allowPrivate);
    }

    /**
     * The search browser's own window switch at the top of Search → "Browser (SERP)". It is a separate browser from
     * the research browser (one per AskAI, headless by default); a change takes effect with the next search.
     */
    static void addSearchBrowserTo(ComicForm form, final WorkspaceStateStore store) {
        final JCheckBox headless = ComicControls.checkBox("Run the search browser headless",
                ResearchRuntimeSettings.loadSearchBrowserHeadless(store));
        headless.addActionListener(e -> {
            ResearchRuntimeSettings.saveSearchBrowserHeadless(store, headless.isSelected());
            com.aresstack.askai.research.runtime.ResearchRuntimeShared.process().setSearchBrowserHeadless(headless.isSelected());
        });
        final javax.swing.JSpinner pauseSeconds = ComicControls.integerSpinner(
                ResearchRuntimeSettings.loadEnginePauseSeconds(store), 0, Integer.MAX_VALUE, 10, 4);
        final javax.swing.JSpinner pauseCapMinutes = ComicControls.integerSpinner(
                ResearchRuntimeSettings.loadEnginePauseCapMinutes(store), 0, Integer.MAX_VALUE, 5, 4);
        pauseSeconds.setToolTipText("After a CAPTCHA or rate limit no search engine is asked for this long; every"
                + " further challenge in a row doubles the pause (0 = no pause). The gap between two Google"
                + " requests is Google's delay under Engines.");
        pauseCapMinutes.setToolTipText("The longest pause the doubling may reach");
        javax.swing.event.ChangeListener savePause = e -> {
            ResearchRuntimeSettings.saveEnginePauseSeconds(store, ((Number) pauseSeconds.getValue()).intValue());
            ResearchRuntimeSettings.saveEnginePauseCapMinutes(store,
                    ((Number) pauseCapMinutes.getValue()).intValue());
            ResearchRuntimeSettings.applyEnginePause(store);
        };
        pauseSeconds.addChangeListener(savePause);
        pauseCapMinutes.addChangeListener(savePause);
        form.row("Search browser", headless);
        form.row("Pause (s)", pauseSeconds);
        form.row("Max pause (min)", pauseCapMinutes);
        form.row("", ComicForm.hint("Headless (default): no window. Pause: after a CAPTCHA no engine is asked."));
    }
}
