package com.aresstack.askai.video;

import com.aresstack.askai.settings.AskAiPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists {@link VideoSettings} as a properties file in the AskAI app directory
 * ({@code %APPDATA%/.askai/video-settings.properties}). The keys mirror the WD4J/corenth
 * {@code SettingsService} names ({@code video.backend}, {@code video.vlc.*}, …) so the file is
 * recognizable to anyone coming from the reference. Instance-based with an explicit path so tests
 * can run against a temp file; the app uses {@link #shared()}.
 */
public final class VideoSettingsStore {

    private static VideoSettingsStore shared;

    private final Path file;

    public VideoSettingsStore(Path file) {
        this.file = file;
    }

    public static synchronized VideoSettingsStore shared() {
        if (shared == null) {
            shared = new VideoSettingsStore(AskAiPaths.appDirectory().resolve("video-settings.properties"));
        }
        return shared;
    }

    public VideoSettings load() {
        VideoSettings settings = new VideoSettings();
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            InputStream in = null;
            try {
                in = Files.newInputStream(file);
                p.load(in);
            } catch (IOException unreadable) {
                // A broken settings file must never break recording — defaults apply.
            } finally {
                closeQuietly(in);
            }
        }
        VideoSettings.General g = settings.getGeneral();
        g.setDefaultBackend(str(p, "video.backend", g.getDefaultBackend()));
        g.setFps(intVal(p, "video.fps", g.getFps()));
        g.setOutputDirectory(str(p, "video.reportsDir", g.getOutputDirectory()));

        VideoSettings.Vlc v = settings.getVlc();
        v.setAutodetect(boolVal(p, "video.vlc.autodetect", v.isAutodetect()));
        v.setBasePath(str(p, "video.vlc.basePath", v.getBasePath()));
        v.setLogEnabled(boolVal(p, "video.vlc.log.enabled", v.isLogEnabled()));
        v.setLogPath(str(p, "video.vlc.log.path", v.getLogPath()));
        v.setVerbose(intVal(p, "video.vlc.verbose", v.getVerbose()));
        v.setMux(str(p, "video.vlc.mux", v.getMux()));
        v.setVideoCodec(str(p, "video.vlc.vcodec", v.getVideoCodec()));
        v.setQuality(str(p, "video.vlc.quality", v.getQuality()));
        v.setCrf(intVal(p, "video.vlc.crf", v.getCrf()));
        v.setBitrateKbps(intVal(p, "video.vlc.bitrateKbps", v.getBitrateKbps()));
        v.setVencPreset(str(p, "video.vlc.venc.preset", v.getVencPreset()));
        v.setVencTune(str(p, "video.vlc.venc.tune", v.getVencTune()));
        v.setDeinterlaceEnabled(boolVal(p, "video.vlc.deinterlace.enabled", v.isDeinterlaceEnabled()));
        v.setDeinterlaceMode(str(p, "video.vlc.deinterlace.mode", v.getDeinterlaceMode()));
        v.setVideoFilter(str(p, "video.vlc.videoFilter", v.getVideoFilter()));
        v.setSoutExtras(str(p, "video.vlc.soutExtras", v.getSoutExtras()));
        v.setAudioEnabled(boolVal(p, "video.vlc.audio.enabled", v.isAudioEnabled()));
        v.setScreenFullscreen(boolVal(p, "video.vlc.screen.fullscreen", v.isScreenFullscreen()));
        v.setScreenLeft(intVal(p, "video.vlc.screen.left", v.getScreenLeft()));
        v.setScreenTop(intVal(p, "video.vlc.screen.top", v.getScreenTop()));
        v.setScreenWidth(intVal(p, "video.vlc.screen.width", v.getScreenWidth()));
        v.setScreenHeight(intVal(p, "video.vlc.screen.height", v.getScreenHeight()));

        VideoSettings.Ffmpeg f = settings.getFfmpeg();
        f.setContainer(str(p, "video.container", f.getContainer()));
        f.setCodecName(str(p, "video.codec", f.getCodecName()));
        f.setPixelFormat(str(p, "video.pixfmt", f.getPixelFormat()));
        f.setQualityMode(str(p, "video.quality", f.getQualityMode()));
        f.setCrf(intVal(p, "video.crf", f.getCrf()));
        f.setQscale(intVal(p, "video.qscale", f.getQscale()));
        f.setBitrateKbps(intVal(p, "video.bitrateKbps", f.getBitrateKbps()));
        f.setPreset(str(p, "video.preset", f.getPreset()));
        f.setTune(str(p, "video.tune", f.getTune()));
        f.setProfile(str(p, "video.profile", f.getProfile()));
        f.setLevel(str(p, "video.level", f.getLevel()));
        f.setThreads(intVal(p, "video.threads", f.getThreads()));
        f.setExtraOptions(str(p, "video.ffopts", f.getExtraOptions()));
        return settings;
    }

    public void save(VideoSettings settings) throws IOException {
        Properties p = new Properties();
        VideoSettings.General g = settings.getGeneral();
        p.setProperty("video.backend", g.getDefaultBackend());
        p.setProperty("video.fps", String.valueOf(g.getFps()));
        p.setProperty("video.reportsDir", g.getOutputDirectory());

        VideoSettings.Vlc v = settings.getVlc();
        p.setProperty("video.vlc.autodetect", String.valueOf(v.isAutodetect()));
        p.setProperty("video.vlc.basePath", v.getBasePath());
        p.setProperty("video.vlc.log.enabled", String.valueOf(v.isLogEnabled()));
        p.setProperty("video.vlc.log.path", v.getLogPath());
        p.setProperty("video.vlc.verbose", String.valueOf(v.getVerbose()));
        p.setProperty("video.vlc.mux", v.getMux());
        p.setProperty("video.vlc.vcodec", v.getVideoCodec());
        p.setProperty("video.vlc.quality", v.getQuality());
        p.setProperty("video.vlc.crf", String.valueOf(v.getCrf()));
        p.setProperty("video.vlc.bitrateKbps", String.valueOf(v.getBitrateKbps()));
        p.setProperty("video.vlc.venc.preset", v.getVencPreset());
        p.setProperty("video.vlc.venc.tune", v.getVencTune());
        p.setProperty("video.vlc.deinterlace.enabled", String.valueOf(v.isDeinterlaceEnabled()));
        p.setProperty("video.vlc.deinterlace.mode", v.getDeinterlaceMode());
        p.setProperty("video.vlc.videoFilter", v.getVideoFilter());
        p.setProperty("video.vlc.soutExtras", v.getSoutExtras());
        p.setProperty("video.vlc.audio.enabled", String.valueOf(v.isAudioEnabled()));
        p.setProperty("video.vlc.screen.fullscreen", String.valueOf(v.isScreenFullscreen()));
        p.setProperty("video.vlc.screen.left", String.valueOf(v.getScreenLeft()));
        p.setProperty("video.vlc.screen.top", String.valueOf(v.getScreenTop()));
        p.setProperty("video.vlc.screen.width", String.valueOf(v.getScreenWidth()));
        p.setProperty("video.vlc.screen.height", String.valueOf(v.getScreenHeight()));

        VideoSettings.Ffmpeg f = settings.getFfmpeg();
        p.setProperty("video.container", f.getContainer());
        p.setProperty("video.codec", f.getCodecName());
        p.setProperty("video.pixfmt", f.getPixelFormat());
        p.setProperty("video.quality", f.getQualityMode());
        p.setProperty("video.crf", String.valueOf(f.getCrf()));
        p.setProperty("video.qscale", String.valueOf(f.getQscale()));
        p.setProperty("video.bitrateKbps", String.valueOf(f.getBitrateKbps()));
        p.setProperty("video.preset", f.getPreset());
        p.setProperty("video.tune", f.getTune());
        p.setProperty("video.profile", f.getProfile());
        p.setProperty("video.level", f.getLevel());
        p.setProperty("video.threads", String.valueOf(f.getThreads()));
        p.setProperty("video.ffopts", f.getExtraOptions());

        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        OutputStream out = Files.newOutputStream(file);
        try {
            p.store(out, "AskAI video recorder settings");
        } finally {
            out.close();
        }
    }

    private static String str(Properties p, String key, String fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : value;
    }

    private static int intVal(Properties p, String key, int fallback) {
        try {
            String value = p.getProperty(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static boolean boolVal(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignore) {
            }
        }
    }
}
