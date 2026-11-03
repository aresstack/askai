package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicForm;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.tts.PiperInstaller;
import com.aresstack.askai.tts.PiperTtsStore;
import com.aresstack.askai.tts.PiperVoice;
import com.aresstack.askai.tts.PiperVoiceCatalog;
import com.aresstack.askai.tts.TextToSpeechSettings;
import com.aresstack.askai.tts.TtsSettingsStore;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The read-aloud voice install section INSIDE the chat settings (Audio &amp; Dictation), right
 * below the speech-output selector: install a curated, natural-sounding Piper voice (HuggingFace
 * {@code rhasspy/piper-voices}) with one click, exactly where the voice is chosen. The engine and
 * every voice run ENTIRELY on the CPU — the GPU stays free for the AI models (GPU acceleration:
 * coming soon). After a successful install the user is asked whether to use the voice right away.
 * The 🔊 entries in Models → Setup → Hugging Face are only a SHORTCUT that opens this section.
 * Mirrors {@link NlpModelsPanel}: pure UI orchestration, installs off the EDT, errors shown,
 * never swallowed.
 */
public final class SpeechOutputModelsPanel extends JPanel {

    /** The one action the panel performs; {@link PiperInstaller#install} matches it. */
    public interface InstallAction {
        void install(PiperVoice voice, PiperInstaller.Progress progress) throws Exception;
    }

    /** The post-install question, injectable so tests never open a dialog. */
    public interface AdoptPrompt {
        boolean confirmUseNow(PiperVoice voice);
    }

    private final PiperTtsStore store;
    private final TtsSettingsStore settings;
    private final InstallAction installer;
    private final AdoptPrompt adoptPrompt;
    private final Executor background;
    private final Executor ui;

    private final Map<String, JLabel> statusByVoice = new LinkedHashMap<String, JLabel>();
    private final Map<String, JButton> buttonByVoice = new LinkedHashMap<String, JButton>();
    private final Map<String, JPanel> rowByVoice = new LinkedHashMap<String, JPanel>();
    private final JLabel error = new JLabel(" ");
    /** Fires after a voice install (and its adoption choice) so the owner's selector can reload. */
    private final java.util.List<Runnable> voicesChangedListeners = new java.util.ArrayList<Runnable>();

    /** Productive wiring: real installer + a background thread + the EDT + a JOptionPane prompt. */
    public SpeechOutputModelsPanel(PiperTtsStore store, TtsSettingsStore settings) {
        this(store, settings, installActionOver(store, settings), null,
                Executors.newSingleThreadExecutor(daemon("tts-install")), edt());
    }

    SpeechOutputModelsPanel(PiperTtsStore store, TtsSettingsStore settings, InstallAction installer,
                            AdoptPrompt adoptPrompt, Executor background, Executor ui) {
        super(new BorderLayout());
        this.store = store;
        this.settings = settings;
        this.installer = installer;
        this.adoptPrompt = adoptPrompt != null ? adoptPrompt : dialogPrompt();
        this.background = background;
        this.ui = ui;
        buildUserInterface();
        refresh();
    }

    private void buildUserInterface() {
        setOpaque(false);
        ComicForm rows = ComicForm.titled("Read-aloud voices (Piper)");
        rows.note("The default speech output is the Windows voice — it needs no download. These model"
                + " voices sound far more natural. CPU (recommended) — the GPU stays free for the AI"
                + " models; GPU acceleration is coming soon.");
        for (PiperVoice voice : PiperVoiceCatalog.curated()) {
            rows.component(buildRow(voice));
        }
        // Full transparency about the downloads — and that the Models route is only a shortcut.
        rows.note("Downloads: voices from HuggingFace “rhasspy/piper-voices”; the engine once (~21 MB)"
                + " from the pinned Piper 2023.11.14-2 release on GitHub. Stored under"
                + " %APPDATA%\\.askai\\tts. The 🔊 entries under Models > Setup > Hugging Face are"
                + " just a shortcut that opens this section.");
        rows.component(error);
        add(rows, BorderLayout.CENTER);
    }

    /** Owner hook: reload the speech-output selector after installs/adoption. */
    public void setOnVoicesChanged(Runnable listener) {
        voicesChangedListeners.clear();
        addVoicesChangedListener(listener);
    }

    /** Another owner (e.g. each chat's voice selector) that reloads after installs/adoption. */
    public void addVoicesChangedListener(Runnable listener) {
        if (listener != null) {
            voicesChangedListeners.add(listener);
        }
    }

    /** Forgets a listener registered with {@link #addVoicesChangedListener(Runnable)} (a closed chat). */
    public void removeVoicesChangedListener(Runnable listener) {
        voicesChangedListeners.remove(listener);
    }

    private void fireVoicesChanged() {
        for (Runnable listener : new java.util.ArrayList<Runnable>(voicesChangedListeners)) {
            listener.run();
        }
    }

    private JPanel buildRow(final PiperVoice voice) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.add(new JLabel(voice.getLanguage() + " — " + voice.getDisplayName()
                + "  ·  ~" + voice.getApproximateSizeMb() + " MB"));
        JLabel status = new JLabel();
        // ONE toggling action per row: Install while absent, Uninstall once installed — decided
        // at CLICK time from the store, so the button and the disk can never disagree.
        JButton action = new ComicButton("Install");
        action.addActionListener(event -> {
            if (store.isVoiceInstalled(voice)) {
                onUninstall(voice);
            } else {
                onInstall(voice);
            }
        });
        statusByVoice.put(voice.getId(), status);
        buttonByVoice.put(voice.getId(), action);
        rowByVoice.put(voice.getId(), row);
        row.add(Box.createHorizontalStrut(8));
        row.add(status);
        row.add(action);
        return row;
    }

    /** Briefly tints the voice's row — the hand-over target of a 🔊 discovery entry. */
    public void highlightVoice(String voiceId) {
        final JPanel row = rowByVoice.get(voiceId);
        if (row == null) {
            return;
        }
        final java.awt.Color original = row.getBackground();
        final boolean wasOpaque = row.isOpaque();
        row.setOpaque(true);
        row.setBackground(ThemeColor.of("models.voiceHighlightBackground")); // calm highlight tint, not an alarm
        row.repaint();
        javax.swing.Timer restore = new javax.swing.Timer(1800, event -> {
            row.setOpaque(wasOpaque);
            row.setBackground(original);
            row.repaint();
        });
        restore.setRepeats(false);
        restore.start();
    }

    /** Recompute installed/selected state for every row ("in use" is per LANGUAGE). */
    void refresh() {
        TextToSpeechSettings tts = settings.load();
        for (PiperVoice voice : PiperVoiceCatalog.curated()) {
            JLabel status = statusByVoice.get(voice.getId());
            JButton button = buttonByVoice.get(voice.getId());
            if (status == null || button == null) {
                continue;
            }
            TextToSpeechSettings.Selection selection = tts.selectionFor(voice.getLanguageCode());
            boolean inUse = selection.getEngine() == TextToSpeechSettings.Engine.PIPER
                    && voice.getId().equals(selection.getVoiceId());
            boolean isInstalled = store.isVoiceInstalled(voice);
            status.setText(!isInstalled ? "Not installed"
                    : inUse ? "Installed · in use" : "Installed");
            button.setText(isInstalled ? "Uninstall" : "Install");
            button.setVisible(true);
            button.setEnabled(true);
        }
    }

    /** Remove the voice cleanly (reinstallable); a selection pointing at it returns to Windows. */
    private void onUninstall(final PiperVoice voice) {
        final JButton button = buttonByVoice.get(voice.getId());
        final JLabel status = statusByVoice.get(voice.getId());
        if (button != null) {
            button.setEnabled(false);
        }
        if (status != null) {
            status.setText("Removing …");
        }
        error.setText(" ");
        background.execute(new Runnable() {
            public void run() {
                Exception failure = null;
                try {
                    store.uninstallVoice(voice);
                    TextToSpeechSettings current = settings.load();
                    TextToSpeechSettings.Selection selection =
                            current.selectionFor(voice.getLanguageCode());
                    if (selection.getEngine() == TextToSpeechSettings.Engine.PIPER
                            && voice.getId().equals(selection.getVoiceId())) {
                        settings.save(current.withSelection(voice.getLanguageCode(),
                                TextToSpeechSettings.Engine.WINDOWS, ""));
                    }
                } catch (Exception ex) {
                    failure = ex;
                }
                final Exception outcome = failure;
                ui.execute(new Runnable() {
                    public void run() {
                        if (outcome != null) {
                            error.setText("Uninstall failed: " + describe(outcome));
                        }
                        refresh();
                        fireVoicesChanged(); // the selector drops the removed voice
                    }
                });
            }
        });
    }

    private void onInstall(final PiperVoice voice) {
        final JButton button = buttonByVoice.get(voice.getId());
        final JLabel status = statusByVoice.get(voice.getId());
        if (button != null) {
            button.setEnabled(false);
        }
        error.setText(" ");
        background.execute(new Runnable() {
            public void run() {
                Exception failure = null;
                try {
                    installer.install(voice, new PiperInstaller.Progress() {
                        public void onProgress(final String stage, final long done, final long total) {
                            ui.execute(new Runnable() {
                                public void run() {
                                    if (status != null) {
                                        status.setText(total > 0
                                                ? stage + " " + (100 * done / total) + "%"
                                                : stage + " …");
                                    }
                                }
                            });
                        }
                    });
                } catch (Exception ex) {
                    failure = ex;
                }
                final Exception outcome = failure;
                ui.execute(new Runnable() {
                    public void run() {
                        if (outcome == null) {
                            offerAdoption(voice);
                            refresh();
                            fireVoicesChanged();
                        } else {
                            error.setText("Install failed: " + describe(outcome));
                            refresh();
                        }
                    }
                });
            }
        });
    }

    /**
     * The frustration-free hand-over: use the fresh voice right away — FOR ITS LANGUAGE — or
     * leave that language on the Windows default.
     */
    private void offerAdoption(PiperVoice voice) {
        if (!adoptPrompt.confirmUseNow(voice)) {
            return;
        }
        try {
            settings.save(settings.load().withSelection(voice.getLanguageCode(),
                    TextToSpeechSettings.Engine.PIPER, voice.getId()));
        } catch (java.io.IOException notSaved) {
            error.setText("Voice installed, but selecting it failed: " + notSaved.getMessage());
        }
    }

    private static String describe(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    // ------------------------------------------------------------------ test hooks

    JButton installButton(String voiceId) {
        return buttonByVoice.get(voiceId);
    }

    String statusText(String voiceId) {
        JLabel label = statusByVoice.get(voiceId);
        return label == null ? null : label.getText();
    }

    String errorText() {
        return error.getText();
    }

    // ------------------------------------------------------------------ productive wiring helpers

    private AdoptPrompt dialogPrompt() {
        return new AdoptPrompt() {
            public boolean confirmUseNow(PiperVoice voice) {
                return JOptionPane.showConfirmDialog(SpeechOutputModelsPanel.this,
                        "Use \"" + voice + "\" for " + voice.getLanguage()
                                + " speech output now?\n\n"
                                + "You can change this anytime right here under\n"
                                + "Settings > Chat > \"Audio & Dictation\" > Speech output · " + voice.getLanguage() + ".",
                        "Voice installed", JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
            }
        };
    }

    private static InstallAction installActionOver(PiperTtsStore store, TtsSettingsStore settings) {
        // Downloads follow AskAI's network settings (proxy, PAC, Windows certificates), read fresh per download.
        final PiperInstaller installer = new PiperInstaller(store, settings, url -> {
            com.aresstack.askai.config.AppConfiguration stored =
                    new com.aresstack.askai.config.AppConfigurationRepository().load();
            return new com.aresstack.askai.net.ConfiguredConnections(stored.getProxyConfiguration(),
                    stored.getCertificateTrustConfiguration(), stored.getHttpClientConfiguration()).open(url);
        });
        return new InstallAction() {
            public void install(PiperVoice voice, PiperInstaller.Progress progress) throws Exception {
                installer.install(voice, progress);
            }
        };
    }

    private static Executor edt() {
        return new Executor() {
            public void execute(Runnable command) {
                SwingUtilities.invokeLater(command);
            }
        };
    }

    private static java.util.concurrent.ThreadFactory daemon(final String name) {
        return new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
