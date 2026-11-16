package com.aresstack.askai.localruntime.onnx;

/** Encoder output: one vector per row ({@link #pooled()}) or one vector per token. Immutable view over the floats. */
final class EncoderOutput {

    private final float[] values;
    private final int rows;
    private final int length;
    private final int width;
    private final boolean pooled;

    private EncoderOutput(float[] values, int rows, int length, int width, boolean pooled) {
        this.values = values;
        this.rows = rows;
        this.length = length;
        this.width = width;
        this.pooled = pooled;
    }

    static EncoderOutput pooled(float[] values, int rows, int width) {
        return new EncoderOutput(values, rows, 1, width, true);
    }

    static EncoderOutput perToken(float[] values, int rows, int length, int width) {
        return new EncoderOutput(values, rows, length, width, false);
    }

    boolean pooled() {
        return pooled;
    }

    int rows() {
        return rows;
    }

    int length() {
        return length;
    }

    int width() {
        return width;
    }

    float value(int row, int position, int dimension) {
        return values[(row * length + position) * width + dimension];
    }
}
