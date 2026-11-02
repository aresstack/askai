package com.aresstack.askai.video.jcodec;

import org.jcodec.api.awt.AWTSequenceEncoder;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * THE single productive JCodec capture path (distilled from the WD4J/corenth {@code JcodecWindowRecorder}):
 * a pure-Java {@link Robot} screen-region grab of a fixed {@link Rectangle}, encoded to H.264/MP4 via
 * JCodec's {@link AWTSequenceEncoder}. No JNA/HWND, no VLC/FFmpeg — so it is the portable default that
 * works without any native runtime. This class owns the technical capture; the {@code MediaRecorder}
 * adapter is a thin wrapper around it. Not Swing-aware.
 *
 * <p>The reference recorder captured a Win32 window handle and fell back to Robot for Swing windows (Win32
 * often yields black there). AskAI IS a Swing app, so the Robot path is both portable AND the reliable one
 * for the primary "record the AskAI window" use case; the flaky HWND path and its overlay/segment/audio
 * machinery are intentionally not ported.</p>
 */
public final class JcodecWindowRecorder {

    private final Rectangle bounds;
    private final Path outputFile;
    private final int fps;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Exception> failure = new AtomicReference<Exception>();
    private Thread worker;
    private CountDownLatch finished;

    public JcodecWindowRecorder(Rectangle bounds, Path outputFile, int fps) {
        this.bounds = new Rectangle(bounds);
        this.outputFile = outputFile;
        this.fps = fps > 0 ? fps : 15;
    }

    /** Begin capturing on a dedicated daemon thread. A probe frame verifies capture works before returning. */
    public void start() throws Exception {
        if (running.get()) {
            return;
        }
        final Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException headless) {
            throw new Exception("Screen capture is not available (headless environment)", headless);
        }
        // Probe once so a broken capture fails at start(), not silently mid-recording.
        BufferedImage probe = robot.createScreenCapture(bounds);
        if (probe == null) {
            throw new Exception("Screen capture returned no image for bounds " + bounds);
        }
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        final AWTSequenceEncoder encoder =
                AWTSequenceEncoder.createSequenceEncoder(new File(outputFile.toString()), fps);
        // JCodec requires even dimensions for H.264; the capture is normalized to even width/height.
        final int captureW = bounds.width - (bounds.width % 2);
        final int captureH = bounds.height - (bounds.height % 2);
        final Rectangle grab = new Rectangle(bounds.x, bounds.y, captureW, captureH);
        running.set(true);
        finished = new CountDownLatch(1);
        worker = new Thread(new Runnable() {
            public void run() {
                long frameNanos = 1_000_000_000L / fps;
                try {
                    while (running.get()) {
                        long frameStart = System.nanoTime();
                        BufferedImage shot = robot.createScreenCapture(grab);
                        encoder.encodeImage(toBgr(shot, captureW, captureH));
                        long sleep = frameNanos - (System.nanoTime() - frameStart);
                        if (sleep > 0) {
                            Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                        }
                    }
                } catch (Exception ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    try {
                        encoder.finish(); // flush the MP4 moov atom — a half-written file would be corrupt
                    } catch (Exception finishFailure) {
                        failure.compareAndSet(null, finishFailure);
                    }
                    finished.countDown();
                }
            }
        }, "jcodec-window-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stop capturing and wait for the encoder to finalize the file. Idempotent. */
    public void stop() throws Exception {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (finished != null) {
            finished.await(10, TimeUnit.SECONDS);
        }
        Exception ex = failure.get();
        if (ex != null) {
            throw ex;
        }
    }

    public boolean isRecording() {
        return running.get();
    }

    /** JCodec's AWT encoder wants a TYPE_3BYTE_BGR image of the exact frame size. */
    private static BufferedImage toBgr(BufferedImage source, int width, int height) {
        BufferedImage bgr = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        bgr.getGraphics().drawImage(source, 0, 0, width, height, null);
        return bgr;
    }
}
