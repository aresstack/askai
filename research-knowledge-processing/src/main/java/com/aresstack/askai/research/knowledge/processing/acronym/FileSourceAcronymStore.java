package com.aresstack.askai.research.knowledge.processing.acronym;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One UTF-8 file per source under {@code <dir>/<safe source id>.acronyms}, written through a temp file and an atomic
 * move. Line format, tab separated with escaped fields:
 * {@code H source checksum version fingerprint}, {@code T normalized kind},
 * {@code L normalized longForm shortStart shortEnd longStart longEnd sentenceStart sentenceEnd}.
 * An unreadable file counts as missing, so the source is simply extracted again.
 */
public final class FileSourceAcronymStore implements SourceAcronymStore {

    private final Path directory;

    public FileSourceAcronymStore(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        this.directory = directory;
    }

    @Override
    public SourceAcronymRecord load(String sourceId) {
        Path file = fileOf(sourceId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8), sourceId);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void save(SourceAcronymRecord record) {
        StringBuilder out = new StringBuilder();
        line(out, "H", record.sourceId(), record.sourceChecksum(), record.extractorVersion(),
                record.segmenterFingerprint());
        for (SourceAcronymRecord.Term term : record.terms()) {
            line(out, "T", term.normalized(), term.kind().name());
            for (SourceAcronymRecord.LongForm form : term.longForms()) {
                line(out, "L", term.normalized(), form.longForm(),
                        String.valueOf(form.shortSpan().start()), String.valueOf(form.shortSpan().end()),
                        String.valueOf(form.longSpan().start()), String.valueOf(form.longSpan().end()),
                        String.valueOf(form.sentenceSpan().start()), String.valueOf(form.sentenceSpan().end()));
            }
        }
        try {
            Files.createDirectories(directory);
            Path file = fileOf(record.sourceId());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, out.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store acronym findings of " + record.sourceId(), e);
        }
    }

    private static SourceAcronymRecord parse(List<String> lines, String expectedSourceId) {
        String[] header = null;
        Map<String, TermKind> kinds = new LinkedHashMap<String, TermKind>();
        Map<String, List<SourceAcronymRecord.LongForm>> forms =
                new LinkedHashMap<String, List<SourceAcronymRecord.LongForm>>();
        for (String raw : lines) {
            if (raw.isEmpty()) {
                continue;
            }
            String[] f = split(raw);
            if ("H".equals(f[0])) {
                header = f;
            } else if ("T".equals(f[0])) {
                kinds.put(f[1], TermKind.valueOf(f[2]));
                forms.put(f[1], new ArrayList<SourceAcronymRecord.LongForm>());
            } else if ("L".equals(f[0])) {
                forms.get(f[1]).add(new SourceAcronymRecord.LongForm(f[2],
                        new TextSpan(Integer.parseInt(f[3]), Integer.parseInt(f[4])),
                        new TextSpan(Integer.parseInt(f[5]), Integer.parseInt(f[6])),
                        new TextSpan(Integer.parseInt(f[7]), Integer.parseInt(f[8]))));
            }
        }
        if (header == null || !expectedSourceId.equals(header[1])) {
            return null;
        }
        List<SourceAcronymRecord.Term> terms = new ArrayList<SourceAcronymRecord.Term>();
        for (Map.Entry<String, TermKind> kind : kinds.entrySet()) {
            terms.add(new SourceAcronymRecord.Term(kind.getKey(), kind.getValue(), forms.get(kind.getKey())));
        }
        return new SourceAcronymRecord(header[1], header[2], header[3], header[4], terms);
    }

    private Path fileOf(String sourceId) {
        StringBuilder safe = new StringBuilder();
        for (char c : sourceId.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                safe.append(c);
            } else {
                safe.append('%').append(String.format("%04x", (int) c));
            }
        }
        return directory.resolve(safe + ".acronyms");
    }

    private static void line(StringBuilder out, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append('\t');
            }
            out.append(escape(fields[i]));
        }
        out.append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String[] split(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                field.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else if (c == '\t') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }
}
