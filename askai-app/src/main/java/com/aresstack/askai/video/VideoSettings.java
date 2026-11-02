package com.aresstack.askai.video;

/**
 * Typed settings for the video recorder (ported from the WD4J/corenth {@code VideoSettingsPanel} key
 * set). One mutable bean per backend, edited by the settings dialog and persisted by
 * {@link VideoSettingsStore}. Deliberately, only settings the PORTED backends actually honor exist
 * here — the reference knobs that belonged to machinery we did not port (JCodec container/threads/
 * audio, FFmpeg color-space/vf-filter/container-fallbacks of the HWND pipeline) are omitted instead
 * of being offered as dead switches.
 */
public final class VideoSettings {

    /** Backend-independent settings ({@code video.backend}, {@code video.fps}, {@code video.reportsDir}). */
    public static final class General {
        private String defaultBackend = "jcodec";
        private int fps = 15;
        private String outputDirectory = System.getProperty("user.home", ".");

        public String getDefaultBackend() { return defaultBackend; }
        public void setDefaultBackend(String defaultBackend) { this.defaultBackend = defaultBackend; }
        public int getFps() { return fps; }
        public void setFps(int fps) { this.fps = fps; }
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
    }

    /** The full VLC setting set of the reference recorder — every field feeds the libvlc args/sout. */
    public static final class Vlc {
        // Installation / diagnostics
        private boolean autodetect = true;
        private String basePath = "";
        private boolean logEnabled = false;
        private String logPath = "";
        private int verbose = 1; // 0 = --quiet, 1..2 = --verbose=n

        // Transcode / output
        private String mux = "mp4";
        private String videoCodec = "h264";
        private String quality = "crf"; // "crf" | "bitrate"
        private int crf = 23;
        private int bitrateKbps = 4000;
        private String vencPreset = "veryfast";
        private String vencTune = "";
        private boolean deinterlaceEnabled = false;
        private String deinterlaceMode = "";
        private String videoFilter = "";
        private String soutExtras = "";
        private boolean audioEnabled = false;

        // screen:// region overrides (0 = use the bounds of the chosen source)
        private boolean screenFullscreen = false;
        private int screenLeft = 0;
        private int screenTop = 0;
        private int screenWidth = 0;
        private int screenHeight = 0;

        public boolean isAutodetect() { return autodetect; }
        public void setAutodetect(boolean autodetect) { this.autodetect = autodetect; }
        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
        public boolean isLogEnabled() { return logEnabled; }
        public void setLogEnabled(boolean logEnabled) { this.logEnabled = logEnabled; }
        public String getLogPath() { return logPath; }
        public void setLogPath(String logPath) { this.logPath = logPath; }
        public int getVerbose() { return verbose; }
        public void setVerbose(int verbose) { this.verbose = verbose; }
        public String getMux() { return mux; }
        public void setMux(String mux) { this.mux = mux; }
        public String getVideoCodec() { return videoCodec; }
        public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
        public String getQuality() { return quality; }
        public void setQuality(String quality) { this.quality = quality; }
        public int getCrf() { return crf; }
        public void setCrf(int crf) { this.crf = crf; }
        public int getBitrateKbps() { return bitrateKbps; }
        public void setBitrateKbps(int bitrateKbps) { this.bitrateKbps = bitrateKbps; }
        public String getVencPreset() { return vencPreset; }
        public void setVencPreset(String vencPreset) { this.vencPreset = vencPreset; }
        public String getVencTune() { return vencTune; }
        public void setVencTune(String vencTune) { this.vencTune = vencTune; }
        public boolean isDeinterlaceEnabled() { return deinterlaceEnabled; }
        public void setDeinterlaceEnabled(boolean deinterlaceEnabled) { this.deinterlaceEnabled = deinterlaceEnabled; }
        public String getDeinterlaceMode() { return deinterlaceMode; }
        public void setDeinterlaceMode(String deinterlaceMode) { this.deinterlaceMode = deinterlaceMode; }
        public String getVideoFilter() { return videoFilter; }
        public void setVideoFilter(String videoFilter) { this.videoFilter = videoFilter; }
        public String getSoutExtras() { return soutExtras; }
        public void setSoutExtras(String soutExtras) { this.soutExtras = soutExtras; }
        public boolean isAudioEnabled() { return audioEnabled; }
        public void setAudioEnabled(boolean audioEnabled) { this.audioEnabled = audioEnabled; }
        public boolean isScreenFullscreen() { return screenFullscreen; }
        public void setScreenFullscreen(boolean screenFullscreen) { this.screenFullscreen = screenFullscreen; }
        public int getScreenLeft() { return screenLeft; }
        public void setScreenLeft(int screenLeft) { this.screenLeft = screenLeft; }
        public int getScreenTop() { return screenTop; }
        public void setScreenTop(int screenTop) { this.screenTop = screenTop; }
        public int getScreenWidth() { return screenWidth; }
        public void setScreenWidth(int screenWidth) { this.screenWidth = screenWidth; }
        public int getScreenHeight() { return screenHeight; }
        public void setScreenHeight(int screenHeight) { this.screenHeight = screenHeight; }
    }

    /** FFmpeg encoder settings — each maps directly onto {@code FFmpegFrameRecorder}. */
    public static final class Ffmpeg {
        private String container = "mp4";
        private String codecName = "libx264";
        private String pixelFormat = "yuv420p";
        private String qualityMode = "crf"; // "crf" | "qscale" | "bitrate"
        private int crf = 23;
        private int qscale = 3;
        private int bitrateKbps = 0;
        private String preset = "veryfast";
        private String tune = "";
        private String profile = "";
        private String level = "";
        private int threads = 0; // 0 = ffmpeg decides
        /** Additional raw FFmpeg video options, one {@code key=value} per line. */
        private String extraOptions = "";

        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
        public String getCodecName() { return codecName; }
        public void setCodecName(String codecName) { this.codecName = codecName; }
        public String getPixelFormat() { return pixelFormat; }
        public void setPixelFormat(String pixelFormat) { this.pixelFormat = pixelFormat; }
        public String getQualityMode() { return qualityMode; }
        public void setQualityMode(String qualityMode) { this.qualityMode = qualityMode; }
        public int getCrf() { return crf; }
        public void setCrf(int crf) { this.crf = crf; }
        public int getQscale() { return qscale; }
        public void setQscale(int qscale) { this.qscale = qscale; }
        public int getBitrateKbps() { return bitrateKbps; }
        public void setBitrateKbps(int bitrateKbps) { this.bitrateKbps = bitrateKbps; }
        public String getPreset() { return preset; }
        public void setPreset(String preset) { this.preset = preset; }
        public String getTune() { return tune; }
        public void setTune(String tune) { this.tune = tune; }
        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public int getThreads() { return threads; }
        public void setThreads(int threads) { this.threads = threads; }
        public String getExtraOptions() { return extraOptions; }
        public void setExtraOptions(String extraOptions) { this.extraOptions = extraOptions; }
    }

    private final General general = new General();
    private final Vlc vlc = new Vlc();
    private final Ffmpeg ffmpeg = new Ffmpeg();

    public General getGeneral() { return general; }
    public Vlc getVlc() { return vlc; }
    public Ffmpeg getFfmpeg() { return ffmpeg; }
}
