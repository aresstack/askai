package com.aresstack.askai.ui;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExitWatchdogTest {

    @Test
    public void forcesExitWhenANonDaemonThreadKeepsTheJvmAlive() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        Thread lingering = new Thread(new Runnable() {
            public void run() {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "lingering-non-daemon");
        lingering.start();
        try {
            assertTrue(ExitWatchdog.blockingThreads().contains(lingering));
            final AtomicInteger status = new AtomicInteger(-1);
            final CountDownLatch exited = new CountDownLatch(1);
            Thread watchdog = ExitWatchdog.start(300L, new ExitWatchdog.Exiter() {
                public void exit(int code) {
                    status.set(code);
                    exited.countDown();
                }
            });
            assertTrue(watchdog.isDaemon());
            assertTrue("watchdog must force the exit", exited.await(5, TimeUnit.SECONDS));
            assertEquals(0, status.get());
        } finally {
            release.countDown();
            lingering.join(2000L);
        }
    }
}
