package com.aresstack.askai.ui;

import java.util.function.BooleanSupplier;

/**
 * What the title bar's ✕ does: a short click hides the main window into the system tray (iconifies when no
 * tray is available); holding it ends AskAI through the regular exit path (tray icon removed, ExitWatchdog
 * armed, dispose -> performShutdown), exactly like the tray's "Exit".
 */
final class MainWindowCloseActions {

    private MainWindowCloseActions() {
    }

    /** Click: hide to the tray, falling back to iconifying the window. */
    static Runnable minimize(final BooleanSupplier hideToTray, final Runnable iconify) {
        return new Runnable() {
            @Override
            public void run() {
                if (!hideToTray.getAsBoolean()) {
                    iconify.run();
                }
            }
        };
    }

    /** Hold: the regular application exit, never the tray. */
    static Runnable exit(final Runnable exitApplication) {
        return exitApplication;
    }
}
