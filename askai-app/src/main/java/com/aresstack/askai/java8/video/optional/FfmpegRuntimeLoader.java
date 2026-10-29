package com.aresstack.askai.java8.video.optional;

import com.aresstack.askai.java8.video.MediaRecorder;
import com.aresstack.askai.java8.video.VideoSettings;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
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
 * <p>The attach is a dedicated {@link BackendClassLoader} over the downloaded jars. Java 9+ has no
 * mutable system classloader any more (it is no {@link URLClassLoader}), so the ONE class that links
 * against JavaCV — {@link FfmpegRecorder} — is defined inside that loader too, from the application's
 * own class bytes, where its {@code org.bytedeco} references resolve. Everything else (the
 * {@link MediaRecorder} contract, the settings) is delegated to the application classloader, so the
 * recorder is a plain {@code MediaRecorder} to its caller. Everything is fetched from Maven Central over
 * HTTPS into {@code ~/.askai/video-libs}.</p>
 */
public final class FfmpegRuntimeLoader {

    private static final String MAVEN = "https://repo1.maven.org/maven2";
    private static final String JAVACV_VER = "1.5.10";
    private static final String JAVACPP_VER = "1.5.10";
    private static final String FFMPEG_VER = "6.1.1-1.5.10";

    /** The one application class that links against JavaCV; defined inside the backend loader. */
    static final String RECORDER_CLASS = "com.aresstack.askai.java8.video.optional.FfmpegRecorder";
    /** Loadable only when the downloaded jars are attached. */
    private static final String PROBE_CLASS = "org.bytedeco.javacv.FFmpegFrameRecorder";

    /** The attached backend; null until the (previously downloaded) jars were attached successfully. */
    private static BackendClassLoader backend;

    /** Progress callback for the (user-confirmed) download; called from the download thread. */
    public interface ProgressListener {
        void onFile(String fileName, int index, int total);
    }

    private FfmpegRuntimeLoader() {
    }

    /** True when the JavaCV/FFmpeg classes are loadable — attaching previously downloaded jars first. */
    public static synchronized boolean isReady() {
        if (backend != null) {
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
        return backend != null;
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
    }

    /**
     * A fresh {@link FfmpegRecorder} created inside the backend loader (the only place where its JavaCV
     * references resolve). Only valid after {@link #isReady()} answered true.
     */
    public static synchronized MediaRecorder newRecorder(VideoSettings.Ffmpeg settings) throws Exception {
        if (!isReady()) {
            throw new IllegalStateException("The FFmpeg libraries are not attached.");
        }
        Class<?> type = Class.forName(RECORDER_CLASS, true, backend);
        Constructor<?> constructor = type.getConstructor(VideoSettings.Ffmpeg.class);
        return (MediaRecorder) constructor.newInstance(settings);
    }

    // ------------------------------------------------------------------ internals

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

    /** Opens the backend loader over the jars and proves the JavaCV classes load before publishing it. */
    private static void attach(List<Path> jars) throws Exception {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        BackendClassLoader candidate =
                new BackendClassLoader(urls, FfmpegRuntimeLoader.class.getClassLoader());
        try {
            Class.forName(PROBE_CLASS, false, candidate);
        } catch (Throwable absent) {
            try {
                candidate.close();
            } catch (IOException ignore) {
            }
            throw new IllegalStateException("The FFmpeg libraries are present in " + libDirectory()
                    + " but their classes could not be loaded: " + absent, absent);
        }
        backend = candidate;
    }

    /**
     * The downloaded JavaCV/FFmpeg jars plus {@link FfmpegRecorder} (and its inner classes), which this
     * loader defines itself from the application's class bytes so the recorder links against the jars.
     * Every other class is delegated parent-first to the application classloader, which keeps the
     * {@link MediaRecorder} contract and the settings types identical on both sides.
     */
    static final class BackendClassLoader extends URLClassLoader {

        BackendClassLoader(URL[] jars, ClassLoader application) {
            super(jars, application);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!isBackendClass(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    byte[] bytes = readFromApplication(name);
                    type = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }

        private static boolean isBackendClass(String name) {
            return name.equals(RECORDER_CLASS) || name.startsWith(RECORDER_CLASS + "$");
        }

        private byte[] readFromApplication(String name) throws ClassNotFoundException {
            InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class");
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) >= 0) {
                    bytes.write(buf, 0, read);
                }
                return bytes.toByteArray();
            } catch (IOException failed) {
                throw new ClassNotFoundException(name, failed);
            } finally {
                try {
                    in.close();
                } catch (IOException ignore) {
                }
            }
        }
    }
}
