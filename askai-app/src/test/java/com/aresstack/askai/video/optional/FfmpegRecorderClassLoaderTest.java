package com.aresstack.askai.video.optional;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Codex review on askai #47: on Java 21 the system classloader takes no new jars, so the downloaded FFmpeg
 * jars live in their own loader. {@link FfmpegRecorder} must be defined there, so its JavaCV references
 * resolve against those jars, while the AskAI interface it implements stays the application's.
 */
public class FfmpegRecorderClassLoaderTest {

    private static final String RECORDER = "com.aresstack.askai.video.optional.FfmpegRecorder";

    @Test
    public void theRecorderIsDefinedNextToTheDownloadedJarsAndLinksAgainstThem() throws Exception {
        String paths = System.getProperty("askai.test.ffmpegJars", "");
        assumeTrue("FFmpeg jars not provided by the build", !paths.isEmpty());
        List<URL> urls = new ArrayList<URL>();
        for (String path : paths.split(File.pathSeparator)) {
            urls.add(new File(path).toURI().toURL());
        }
        ClassLoader app = FfmpegRecorderClassLoaderTest.class.getClassLoader();
        try {
            Class.forName("org.bytedeco.javacv.FFmpegFrameRecorder", false, app);
            throw new AssertionError("JavaCV must not be on the application classpath for this test");
        } catch (ClassNotFoundException expected) {
            // the situation of a user after the confirmed download
        }

        FfmpegRuntimeLoader.RecorderClassLoader loader =
                new FfmpegRuntimeLoader.RecorderClassLoader(urls.toArray(new URL[0]), app);
        Class<?> recorder = Class.forName(RECORDER, true, loader);
        assertSame(loader, recorder.getClassLoader());
        assertNotSame(Class.forName(RECORDER, false, app), recorder);
        assertTrue(com.aresstack.askai.video.MediaRecorder.class.isAssignableFrom(recorder));
        // Resolving every member signature links the JavaCV types through the dedicated loader.
        for (Method m : recorder.getDeclaredMethods()) {
            m.getReturnType();
        }
        recorder.getDeclaredFields();
        Class<?> javacv = Class.forName("org.bytedeco.javacv.FFmpegFrameRecorder", false, loader);
        assertSame(loader, javacv.getClassLoader());
        loader.close();
    }
}
