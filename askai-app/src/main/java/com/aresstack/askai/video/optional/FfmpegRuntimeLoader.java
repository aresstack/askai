package com.aresstack.askai.video.optional;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the JavaCV/FFmpeg jars (incl. the large platform natives) at runtime — ported from the
 * WD4J/corenth {@code VideoRuntimeLoader}. AskAI never ships these jars and NEVER downloads them on its
 * own: {@link #downloadAndAttach} may only be called after the user EXPLICITLY confirmed the download
 * (the Record Video dialog asks with a Yes/No prompt listing the exact files). Once the jars sit in the
 * local lib dir — i.e. after that one confirmed download — they are attached silently on later runs,
 * which is the persisted result of the user's earlier consent, not a new download.
 *
 * <p>The jars are attached through a dedicated classloader (the system classloader of Java 9+ cannot take
 * new jars): {@link FfmpegRecorder} itself is defined in that loader, so its JavaCV references resolve
 * there, while the AskAI types it implements come from the application. Everything is fetched from Maven
 * Central over HTTPS into {@code ~/.askai/video-libs}.</p>
 */
public final class FfmpegRuntimeLoader {

    private static final String MAVEN = "https://repo1.maven.org/maven2";
    private static final String JAVACV_VER = "1.5.10";
    private static final String JAVACPP_VER = "1.5.10";
    private static final String FFMPEG_VER = "6.1.1-1.5.10";

    /** Progress callback for the (user-confirmed) download; called from the download thread. */
    public interface ProgressListener {
        void onFile(String fileName, int index, int total);
    }

    private static final String RECORDER_CLASS = "com.aresstack.askai.video.optional.FfmpegRecorder";

    /** The loader holding the downloaded jars and {@link FfmpegRecorder}; null until attached. */
    private static ClassLoader attached;

    private FfmpegRuntimeLoader() {
    }

    /** A new FFmpeg recorder, defined in the loader that sees the JavaCV classes. */
    static synchronized com.aresstack.askai.video.MediaRecorder newRecorder(
            com.aresstack.askai.video.VideoSettings.Ffmpeg settings) {
        ClassLoader loader = attached != null ? attached : FfmpegRuntimeLoader.class.getClassLoader();
        try {
            return (com.aresstack.askai.video.MediaRecorder) Class.forName(RECORDER_CLASS, true, loader)
                    .getConstructor(com.aresstack.askai.video.VideoSettings.Ffmpeg.class)
                    .newInstance(settings);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("The FFmpeg recorder could not be created: " + cause, cause);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("The FFmpeg recorder could not be created: " + e, e);
        }
    }

    /** True when the JavaCV/FFmpeg classes are loadable — attaching previously downloaded jars first. */
    public static synchronized boolean isReady() {
        if (classesPresent()) {
            return true;
        }
        if (!allJarsPresent()) {
            return false;
        }
        try {
            attach(localJars());
        } catch (Exception attachFailed) {
            return false;
        }
        return classesPresent();
    }

    /** True when this platform is supported AND nothing was downloaded yet (a download would be needed). */
    public static boolean isDownloadRequired() {
        return platformClassifier() != null && !isReady();
    }

    /** The exact files a download would fetch — shown to the user BEFORE asking for confirmation. */
    public static List<String> requiredDownloadUrls() {
        List<String> urls = new ArrayList<String>();
        String classifier = platformClassifier();
        if (classifier == null) {
            return urls;
        }
        urls.add(mavenUrl("org/bytedeco/javacv", JAVACV_VER, "javacv-" + JAVACV_VER + ".jar"));
        urls.add(mavenUrl("org/bytedeco/javacpp", JAVACPP_VER, "javacpp-" + JAVACPP_VER + ".jar"));
        urls.add(mavenUrl("org/bytedeco/javacpp", JAVACPP_VER,
                "javacpp-" + JAVACPP_VER + "-" + classifier + ".jar"));
        urls.add(mavenUrl("org/bytedeco/ffmpeg", FFMPEG_VER, "ffmpeg-" + FFMPEG_VER + ".jar"));
        urls.add(mavenUrl("org/bytedeco/ffmpeg", FFMPEG_VER,
                "ffmpeg-" + FFMPEG_VER + "-" + classifier + ".jar"));
        return urls;
    }

    /** Where the jars are persisted after the confirmed download. */
    public static Path libDirectory() {
        return Paths.get(System.getProperty("user.home", "."), ".askai", "video-libs");
    }

    /**
     * Download the missing jars and attach them. MUST only be called after the user's explicit,
     * confirmed request — this method itself shows no UI and performs no confirmation.
     */
    public static synchronized void downloadAndAttach(ProgressListener progress) throws Exception {
        if (isReady()) {
            return;
        }
        List<String> urls = requiredDownloadUrls();
        if (urls.isEmpty()) {
            throw new IllegalStateException(
                    "This platform is not supported by the FFmpeg backend (needs a 64-bit OS).");
        }
        Path dir = libDirectory();
        Files.createDirectories(dir);
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            if (progress != null) {
                progress.onFile(fileName, i + 1, urls.size());
            }
            Path target = dir.resolve(fileName);
            if (!Files.exists(target)) {
                downloadToFile(url, target);
            }
        }
        attach(localJars());
        if (!classesPresent()) {
            throw new IllegalStateException("The FFmpeg libraries were downloaded but could not be "
                    + "loaded.");
        }
    }

    // ------------------------------------------------------------------ internals

    private static boolean classesPresent() {
        try {
            Class.forName("org.bytedeco.javacv.FFmpegFrameRecorder", false,
                    attached != null ? attached : FfmpegRuntimeLoader.class.getClassLoader());
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    private static boolean allJarsPresent() {
        List<String> urls = requiredDownloadUrls();
        if (urls.isEmpty()) {
            return false;
        }
        Path dir = libDirectory();
        for (int i = 0; i < urls.size(); i++) {
            String fileName = urls.get(i).substring(urls.get(i).lastIndexOf('/') + 1);
            if (!Files.exists(dir.resolve(fileName))) {
                return false;
            }
        }
        return true;
    }

    private static List<Path> localJars() {
        List<Path> jars = new ArrayList<Path>();
        List<String> urls = requiredDownloadUrls();
        for (int i = 0; i < urls.size(); i++) {
            String fileName = urls.get(i).substring(urls.get(i).lastIndexOf('/') + 1);
            jars.add(libDirectory().resolve(fileName));
        }
        return jars;
    }

    /** Maven Central classifier for this platform; null when unsupported (32-bit, exotic arch). */
    public static String platformClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        boolean x64 = arch.contains("amd64") || arch.contains("x86_64");
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return x64 ? "windows-x86_64" : null;
        }
        if (os.contains("mac")) {
            return arm64 ? "macosx-arm64" : (x64 ? "macosx-x86_64" : null);
        }
        if (os.contains("linux")) {
            return arm64 ? "linux-arm64" : (x64 ? "linux-x86_64" : null);
        }
        return null;
    }

    private static String mavenUrl(String groupPath, String version, String file) {
        return MAVEN + "/" + groupPath + "/" + version + "/" + file;
    }

    private static void downloadToFile(String urlStr, Path target) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "AskAI-VideoLoader/1.0");
        conn.connect();
        if (conn.getResponseCode() / 100 != 2) {
            conn.disconnect();
            throw new IOException("HTTP " + conn.getResponseCode() + " for " + urlStr);
        }
        // Download to a temp file first so an aborted transfer never looks like a complete jar.
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        BufferedInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new BufferedInputStream(conn.getInputStream());
            out = new FileOutputStream(partial.toFile());
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignore) { }
            }
            if (in != null) {
                try { in.close(); } catch (IOException ignore) { }
            }
            conn.disconnect();
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void attach(List<Path> jars) throws IOException {
        if (attached != null) {
            return;
        }
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        attached = new RecorderClassLoader(urls, FfmpegRuntimeLoader.class.getClassLoader());
    }

    /**
     * Parent-first, except for {@link FfmpegRecorder} (and its nested classes): those are defined here from
     * the application's class bytes, so their JavaCV references resolve against the downloaded jars.
     */
    static final class RecorderClassLoader extends URLClassLoader {

        static {
            ClassLoader.registerAsParallelCapable();
        }

        RecorderClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(RECORDER_CLASS) && !name.startsWith(RECORDER_CLASS + "$")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    String resource = name.replace('.', '/') + ".class";
                    java.io.InputStream in = getParent().getResourceAsStream(resource);
                    if (in == null) {
                        throw new ClassNotFoundException(name);
                    }
                    try {
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = in.read(buf)) >= 0) {
                            bytes.write(buf, 0, read);
                        }
                        byte[] b = bytes.toByteArray();
                        c = defineClass(name, b, 0, b.length, FfmpegRuntimeLoader.class.getProtectionDomain());
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    } finally {
                        try { in.close(); } catch (IOException ignore) { }
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}
