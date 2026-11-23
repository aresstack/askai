package com.aresstack.askai.localruntime.onnx;

/** A padded token batch (ids, attention mask, token types). */
final class TokenBatch {

    final long[][] ids;
    final long[][] mask;
    final long[][] typeIds;

    private TokenBatch(long[][] ids, long[][] mask, long[][] typeIds) {
        this.ids = ids;
        this.mask = mask;
        this.typeIds = typeIds;
    }

    /** Pad rows to the longest one with {@code padId}; {@code types} may be null. */
    static TokenBatch pad(int[][] rows, int[][] types, long padId) {
        int length = 1;
        for (int[] row : rows) {
            length = Math.max(length, row.length);
        }
        long[][] ids = new long[rows.length][length];
        long[][] mask = new long[rows.length][length];
        long[][] typeIds = new long[rows.length][length];
        for (int r = 0; r < rows.length; r++) {
            for (int p = 0; p < length; p++) {
                boolean token = p < rows[r].length;
                ids[r][p] = token ? rows[r][p] : padId;
                mask[r][p] = token ? 1 : 0;
                typeIds[r][p] = token && types != null ? types[r][p] : 0;
            }
            if (rows[r].length == 0) {
                mask[r][0] = 1;
            }
        }
        return new TokenBatch(ids, mask, typeIds);
    }
}
