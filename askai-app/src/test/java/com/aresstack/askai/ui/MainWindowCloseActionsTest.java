package com.aresstack.askai.ui;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** Click on the title-bar ✕ goes to the tray; holding it exits the application. */
public class MainWindowCloseActionsTest {

    @Test
    public void clickHidesToTray() {
        AtomicInteger hidden = new AtomicInteger();
        AtomicInteger iconified = new AtomicInteger();
        MainWindowCloseActions.minimize(() -> { hidden.incrementAndGet(); return true; },
                iconified::incrementAndGet).run();
        assertEquals(1, hidden.get());
        assertEquals(0, iconified.get());
    }

    @Test
    public void clickIconifiesWithoutTray() {
        AtomicInteger iconified = new AtomicInteger();
        MainWindowCloseActions.minimize(() -> false, iconified::incrementAndGet).run();
        assertEquals(1, iconified.get());
    }

    @Test
    public void holdExitsInsteadOfHidingToTray() {
        AtomicInteger hidden = new AtomicInteger();
        AtomicInteger exited = new AtomicInteger();
        Runnable minimize = MainWindowCloseActions.minimize(() -> { hidden.incrementAndGet(); return true; },
                () -> { });
        Runnable exit = MainWindowCloseActions.exit(exited::incrementAndGet);
        exit.run();
        assertEquals(1, exited.get());
        assertEquals(0, hidden.get());
        minimize.run();
        assertEquals(1, exited.get());
    }
}
