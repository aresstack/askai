package com.aresstack.askai.video;

import java.nio.file.Path;

/**
 * Immutable configuration for one recording (ported from the WD4J/corenth {@code RecordingProfile}, plus a
 * neutral {@link RecordingSource}). No long primitive argument lists reach the UI or controller — they pass
 * this value object. Java 8: no records, no {@code var}.
 */
public final class RecordingProfile {

    private final RecordingSource source;
    private final Path outputFile;
    private final int width;
    private final int height;
    private final int fps;
    private final String videoCodec;
    private final String audioCodec;

    private RecordingProfile(Builder builder) {
        this.source = builder.source;
        this.outputFile = builder.outputFile;
        this.width = builder.width;
        this.height = builder.height;
        this.fps = builder.fps;
        this.videoCodec = builder.videoCodec;
        this.audioCodec = builder.audioCodec;
    }

    public RecordingSource getSource() {
        return source;
    }

    public Path getOutputFile() {
        return outputFile;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFps() {
        return fps;
    }

    /** Optional video codec hint for backends that support a choice; may be {@code null}. */
    public String getVideoCodec() {
        return videoCodec;
    }

    /** Optional audio codec hint; {@code null} means no audio / backend default. */
    public String getAudioCodec() {
        return audioCodec;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private RecordingSource source;
        private Path outputFile;
        private int width;
        private int height;
        private int fps = 15;
        private String videoCodec;
        private String audioCodec;

        private Builder() {
        }

        public Builder source(RecordingSource source) {
            this.source = source;
            if (source != null && (width <= 0 || height <= 0)) {
                this.width = source.getBounds().width;
                this.height = source.getBounds().height;
            }
            return this;
        }

        public Builder outputFile(Path outputFile) {
            this.outputFile = outputFile;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public Builder fps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder videoCodec(String videoCodec) {
            this.videoCodec = videoCodec;
            return this;
        }

        public Builder audioCodec(String audioCodec) {
            this.audioCodec = audioCodec;
            return this;
        }

        public RecordingProfile build() {
            if (source == null) {
                throw new IllegalArgumentException("source must not be null");
            }
            if (outputFile == null) {
                throw new IllegalArgumentException("outputFile must not be null");
            }
            if (fps <= 0) {
                throw new IllegalArgumentException("fps must be > 0");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be > 0");
            }
            return new RecordingProfile(this);
        }
    }

    @Override
    public String toString() {
        return "RecordingProfile{source=" + source + ", outputFile=" + outputFile
                + ", " + width + "x" + height + "@" + fps + "fps"
                + ", videoCodec=" + videoCodec + ", audioCodec=" + audioCodec + "}";
    }
}
