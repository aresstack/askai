package com.aresstack.askai.research.drafting;

import java.util.ArrayList;
import java.util.List;

/** A line difference ({@code - old}, {@code + new}) for "Rewrite section" to show what changed. */
public final class TextDiff {

    private TextDiff() {
    }

    public static String lines(String before, String after) {
        String[] a = split(before);
        String[] b = split(after);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<String>();
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) {
                out.add("  " + a[i]);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("- " + a[i++]);
            } else {
                out.add("+ " + b[j++]);
            }
        }
        while (i < a.length) {
            out.add("- " + a[i++]);
        }
        while (j < b.length) {
            out.add("+ " + b[j++]);
        }
        StringBuilder text = new StringBuilder();
        for (String line : out) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    private static String[] split(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        return text.replace("\r\n", "\n").split("\n", -1);
    }
}
