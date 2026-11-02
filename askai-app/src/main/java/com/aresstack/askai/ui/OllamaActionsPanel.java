package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.client.OllamaModelInfoView;
import com.aresstack.askai.client.OllamaPullProgress;
import com.aresstack.askai.client.OllamaRunningModelInfo;
import com.aresstack.askai.service.FeatureAction;
import com.aresstack.askai.service.FeatureActionService;
import com.aresstack.askai.service.OllamaService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;

/**
 * Functional tools panel exercising the full ollama4j surface AskAI wires.
 *
 * <p>Each capability card runs a real {@link OllamaService} operation off the EDT and
 * reports into the log. Actions that need input read the shared model/input fields.
 * Capabilities AskAI deliberately keeps out of scope are described through
 * {@link FeatureActionService}.</p>
 */
public final class OllamaActionsPanel extends JPanel {

    private final FeatureActionService featureActionService;
    private final OllamaService ollamaService;

    private final JTextField modelField;
    private final JTextField inputField;
    private final JProgressBar progressBar;
    private final JTextArea logArea;

    public OllamaActionsPanel(FeatureActionService featureActionService, OllamaService ollamaService) {
        this.featureActionService = featureActionService;
        this.ollamaService = ollamaService;
        this.modelField = ComicControls.textField(24);
        this.inputField = ComicControls.textField(32);
        this.progressBar = ComicControls.progressBar(0, 100);
        this.logArea = ComicControls.textArea(10, 80);
        buildUserInterface();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        add(buildHeader(), BorderLayout.NORTH);
        add(new JScrollPane(buildGrid()), BorderLayout.CENTER);
        add(buildLog(), BorderLayout.SOUTH);
    }

    private JComponent buildHeader() {
        JLabel title = new JLabel("Ollama Actions");
        JLabel subtitle = new JLabel("Live operations against the configured Ollama server via ollama4j.");
        subtitle.setForeground(ThemeColor.of("models.secondaryText"));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 4, 2, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridx = 0;
        constraints.gridy = 0;
        form.add(new JLabel("Model"), constraints);
        constraints.gridx = 1;
        constraints.weightx = 0.4d;
        form.add(modelField, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0.0d;
        form.add(new JLabel("Prompt / text"), constraints);
        constraints.gridx = 3;
        constraints.weightx = 0.6d;
        form.add(inputField, constraints);

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(title, BorderLayout.NORTH);
        top.add(subtitle, BorderLayout.CENTER);

        JPanel header = new JPanel(new BorderLayout(6, 6));
        header.add(top, BorderLayout.NORTH);
        header.add(form, BorderLayout.CENTER);
        progressBar.setStringPainted(true);
        header.add(progressBar, BorderLayout.SOUTH);
        return header;
    }

    private JComponent buildGrid() {
        JPanel grid = new JPanel(new GridLayout(0, 3, 10, 10));
        for (FeatureAction action : featureActionService.actions()) {
            grid.add(card(action));
        }
        return grid;
    }

    private JComponent buildLog() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setText("Select an action. Operations run against the configured server.");
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(ComicControls.titledBorder("Action log"));
        return scroll;
    }

    private JPanel card(final FeatureAction action) {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(
                ComicControls.titledBorder(action.getTitle()),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        JLabel body = new JLabel("<html>" + action.getDescription() + "</html>");
        panel.add(body, BorderLayout.CENTER);

        if (action.isAvailable()) {
            JButton button = new ComicButton("Run");
            button.addActionListener(event -> run(action.getId()));
            panel.add(button, BorderLayout.SOUTH);
        } else {
            // Not wired yet: don't offer a Run button that would only fail after the click; label the
            // card as experimental so it doesn't read as a usable feature.
            panel.add(experimentalBadge(), BorderLayout.SOUTH);
            body.setForeground(ThemeColor.of("models.unavailableText"));
        }
        return panel;
    }

    /** A muted "not available yet" chip, matching the badge style used on model cards. */
    private static JComponent experimentalBadge() {
        JLabel badge = new JLabel("Experimental — not available yet");
        badge.setOpaque(true);
        badge.setForeground(ThemeColor.of("bubble.userForeground"));
        badge.setBackground(ThemeColor.of("models.unavailableBadge"));
        badge.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        wrap.add(badge);
        return wrap;
    }

    private void run(String actionId) {
        if ("server-health".equals(actionId)) {
            runServerHealth();
        } else if ("list-running".equals(actionId)) {
            runListRunning();
        } else if ("model-details".equals(actionId)) {
            runModelDetails();
        } else if ("pull-model".equals(actionId)) {
            runPull();
        } else if ("unload-model".equals(actionId)) {
            runUnload();
        } else if ("generate".equals(actionId)) {
            runGenerate();
        } else if ("embed".equals(actionId)) {
            runEmbed();
        } else {
            runInformational(actionId);
        }
    }

    private void runServerHealth() {
        log("Checking server health ...");
        ollamaService.getServerVersion(new OllamaService.ServerVersionListener() {
            @Override
            public void onServerVersion(String version) {
                log("Server reachable. Version " + version + ".");
            }

            @Override
            public void onError(Exception ex) {
                log("Server not reachable: " + ex.getMessage());
            }
        });
    }

    private void runListRunning() {
        log("Listing running models ...");
        ollamaService.listRunningModels(new OllamaService.RunningModelsListener() {
            @Override
            public void onRunningModels(List<OllamaRunningModelInfo> models) {
                if (models.isEmpty()) {
                    log("No models currently loaded.");
                    return;
                }
                StringBuilder builder = new StringBuilder("Running models:");
                for (OllamaRunningModelInfo info : models) {
                    builder.append("\n  - ").append(info.getDisplayName());
                }
                log(builder.toString());
            }

            @Override
            public void onError(Exception ex) {
                log("Could not list running models: " + ex.getMessage());
            }
        });
    }

    private void runModelDetails() {
        final String modelName = requireModel();
        if (modelName == null) {
            return;
        }
        log("Reading details for " + modelName + " ...");
        ollamaService.getModelInfo(modelName, new OllamaService.ModelInfoListener() {
            @Override
            public void onModelInfo(OllamaModelInfoView info) {
                StringBuilder builder = new StringBuilder("Details for " + modelName + ":");
                builder.append("\n  family: ").append(info.getDetails().getFamily());
                builder.append("\n  parameters: ").append(info.getDetails().getParameterSize());
                builder.append("\n  quantization: ").append(info.getDetails().getQuantizationLevel());
                if (!info.getCapabilities().isEmpty()) {
                    builder.append("\n  capabilities: ").append(String.join(", ", info.getCapabilities()));
                }
                if (!info.getTemplate().isEmpty()) {
                    builder.append("\n  template: ").append(firstLine(info.getTemplate()));
                }
                log(builder.toString());
            }

            @Override
            public void onError(Exception ex) {
                log("Could not read details: " + ex.getMessage());
            }
        });
    }

    private void runPull() {
        final String modelName = requireModel();
        if (modelName == null) {
            return;
        }
        log("Pulling " + modelName + " ...");
        progressBar.setValue(0);
        ollamaService.pullModel(modelName, new OllamaService.PullListener() {
            @Override
            public void onProgress(final OllamaPullProgress progress) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        int percent = progress.percent();
                        if (percent >= 0) {
                            progressBar.setIndeterminate(false);
                            progressBar.setValue(percent);
                            progressBar.setString(progress.getStatus() + " " + percent + "%");
                        } else {
                            progressBar.setIndeterminate(true);
                            progressBar.setString(progress.getStatus());
                        }
                    }
                });
            }

            @Override
            public void onComplete(final String message) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        progressBar.setIndeterminate(false);
                        progressBar.setValue(100);
                        progressBar.setString("Done");
                        log(message);
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        progressBar.setIndeterminate(false);
                        progressBar.setString("Failed");
                        log("Pull failed: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void runUnload() {
        final String modelName = requireModel();
        if (modelName == null) {
            return;
        }
        log("Unloading " + modelName + " ...");
        ollamaService.unloadModel(modelName, simpleAction("Unload"));
    }

    private void runGenerate() {
        final String modelName = requireModel();
        if (modelName == null) {
            return;
        }
        final String prompt = inputField.getText().trim();
        if (prompt.isEmpty()) {
            log("Enter a prompt in the Prompt / text field first.");
            return;
        }
        log("Generating with " + modelName + " ...");
        ollamaService.generate(modelName, prompt, simpleAction("Generate"));
    }

    private void runEmbed() {
        final String modelName = requireModel();
        if (modelName == null) {
            return;
        }
        final String text = inputField.getText().trim();
        if (text.isEmpty()) {
            log("Enter text in the Prompt / text field first.");
            return;
        }
        log("Embedding with " + modelName + " ...");
        ollamaService.embed(modelName, text, new OllamaService.EmbedListener() {
            @Override
            public void onEmbedding(int vectorCount, int dimensions) {
                log("Embedded " + vectorCount + " vector(s), " + dimensions + " dimensions.");
            }

            @Override
            public void onError(Exception ex) {
                log("Embedding failed: " + ex.getMessage());
            }
        });
    }

    private void runInformational(String actionId) {
        featureActionService.execute(actionId, new FeatureActionService.FeatureActionListener() {
            @Override
            public void onAccepted(String title, String message) {
                log(title + ": " + message);
            }
        });
    }

    private OllamaService.ActionListener simpleAction(final String label) {
        return new OllamaService.ActionListener() {
            @Override
            public void onComplete(String message) {
                log(message);
            }

            @Override
            public void onError(Exception ex) {
                log(label + " failed: " + ex.getMessage());
            }
        };
    }

    private String requireModel() {
        String modelName = modelField.getText().trim();
        if (modelName.isEmpty()) {
            log("Enter a model name in the Model field first.");
            return null;
        }
        return modelName;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline) + " ...";
    }

    private void log(final String message) {
        onUi(new Runnable() {
            @Override
            public void run() {
                logArea.append("\n" + message);
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    private static void onUi(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }
}
