package com.aresstack.askai.credentials;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingCallback;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AskAI's one KeePass link ({@link KeePassLink}, taken from Space Harvester): the OpenAI-compatible API's base URL and
 * key can come from a KeePass entry (its URL field and password). KeePass needs the KeePassRPC plugin, which is not
 * part of a normal KeePass installation ({@link #PLUGIN_HINT}). Everything except {@link #paired()} blocks: call it off
 * the EDT.
 */
public final class AskAiKeePass implements KeePassAccess {

    /** Where the KeePassRPC plugin comes from. */
    public static final String PLUGIN_URL = "https://github.com/kee-org/keepassrpc/releases";
    /** Shown wherever KeePass can be chosen: the plugin is not part of KeePass. */
    public static final String PLUGIN_HINT = "Needs KeePass with the KeePassRPC plugin (" + PLUGIN_URL + "), which "
            + "a normal KeePass installation does not have: copy KeePassRPC.plgx into KeePass's Plugins folder and "
            + "restart KeePass. Without it, store the key in AskAI instead.";

    private static AskAiKeePass shared;

    private final KeePassLink link;

    AskAiKeePass(KeePassLink link) {
        this.link = link;
    }

    /** The link of this AskAI installation; its pairing key lies in {@code keepass/} of the configuration folder. */
    public static synchronized AskAiKeePass shared() {
        if (shared == null) {
            File directory = new File(com.aresstack.askai.modelport.ModelProviderSettingsRepository
                    .inConfigurationDirectory().getFile().getAbsoluteFile().getParentFile(),
                    KeePassLink.PAIRING_DIRECTORY);
            shared = new AskAiKeePass(new KeePassLink(directory.toPath(), new SwingPairing()));
        }
        return shared;
    }

    /** Lets the model provider settings read API keys from KeePass (idempotent). */
    public static void install() {
        com.aresstack.askai.modelport.ModelProviderSettingsRepository.inConfigurationDirectory()
                .useKeePass(entry -> shared().apiKey(entry));
    }

    @Override
    public boolean paired() {
        return link.paired();
    }

    @Override
    public List<String> titles() throws IOException {
        try {
            return link.titles();
        } catch (SecretUnavailableException unavailable) {
            throw new IOException(reason(unavailable), unavailable);
        }
    }

    @Override
    public String location(String title) throws IOException {
        try (VaultEntryDraft read = link.readSilently(title)) {
            return read.location() == null ? "" : read.location().trim();
        } catch (SecretUnavailableException unavailable) {
            throw new IOException(reason(unavailable), unavailable);
        }
    }

    @Override
    public void unpair() {
        link.unpair();
    }

    @Override
    public char[] apiKey(String title) throws IOException {
        try (VaultEntryDraft read = link.readSilently(title)) {
            char[] key = read.copyPassword();
            if (key.length == 0) {
                throw new IOException("the KeePass entry \"" + title + "\" has no password (the API key)");
            }
            return key;
        } catch (SecretUnavailableException unavailable) {
            throw new IOException("the API key could not be read from KeePass: " + reason(unavailable), unavailable);
        }
    }

    /** Why KeePass gave nothing, in words the user can act on (the plugin first: it is what colleagues lack). */
    public static String reason(SecretUnavailableException error) {
        switch (error.reason()) {
            case NOT_AVAILABLE:
                return "KeePass is not running or the KeePassRPC plugin is missing (" + PLUGIN_URL + "); start "
                        + "KeePass and connect again";
            case ACCESS_DENIED:
                return "KeePass refused the connection; connect again";
            case CANCELLED:
                return "connecting to KeePass was cancelled";
            case NOT_FOUND:
                return "KeePass has no entry with this name";
            case ALREADY_EXISTS:
                return "an entry with this name exists already in KeePass and stays as it is";
            default:
                return String.valueOf(error.getMessage());
        }
    }

    /** Asks on the EDT for the one-time password KeePass shows while connecting; cancel means {@code null}. */
    private static final class SwingPairing implements KeePassPairingCallback {
        @Override
        public char[] requestPairingPassword(final String clientDisplayName) {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return null;
            }
            final AtomicReference<char[]> typed = new AtomicReference<char[]>();
            Runnable ask = () -> {
                JPasswordField field = com.aresstack.comiccontrols.control.ComicControls.passwordField(20);
                JPanel panel = new JPanel(new BorderLayout(0, 6));
                panel.add(new JLabel("<html>KeePass now shows a one-time password for \"" + clientDisplayName
                        + "\".<br>Please enter it here.</html>"), BorderLayout.NORTH);
                panel.add(field, BorderLayout.CENTER);
                int answer = JOptionPane.showConfirmDialog(null, panel, "Connect to KeePass",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                char[] password = field.getPassword();
                if (answer == JOptionPane.OK_OPTION && password.length > 0) {
                    typed.set(password);
                } else {
                    Arrays.fill(password, '\0');
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                ask.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(ask);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (java.lang.reflect.InvocationTargetException e) {
                    return null;
                }
            }
            return typed.get();
        }
    }
}
