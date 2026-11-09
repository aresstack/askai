package com.aresstack.askai.research.runtime.writer;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 1 of the claim check (plan D8, PR 28): every number, year and percentage and every quotation of a sentence
 * must occur in a passage that the sentence, or its paragraph, cites. Decimal and thousands separators, percent signs
 * and quotation marks are normalised first, so "15,5 %" matches "15.5 percent". A miss becomes an
 * {@code unsupported number} or {@code unsupported quote} flag; nothing is removed. Proper names are not checked,
 * because German capitalises every noun. Stateless and deterministic.
 */
public final class ClaimSupportCheck {

    /** Digits with optional internal separators; the narrow and no-break spaces and the apostrophe group thousands. */
    static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,\u00A0\u202F\u2009']\\d+)*");
    private static final Pattern[] QUOTES = {
            Pattern.compile("\u201E([^\u201C\u201D\"]{2,}?)[\u201C\u201D\"]"), // „…“
            Pattern.compile("\u201C([^\u201D\u201E]{2,}?)\u201D"),             // “…”
            Pattern.compile("\u00AB([^\u00BB]{2,}?)\u00BB"),                   // «…»
            Pattern.compile("\u00BB([^\u00AB]{2,}?)\u00AB"),                   // »…«
            Pattern.compile("\"([^\"]{2,}?)\"")                                // "…"
    };
    /** Quotations of a single word are emphasis ("so-called"), not quotes of a source. */
    static final int MIN_QUOTE_WORDS = 2;

    /**
     * @param result   the validated answer
     * @param passages the evidence pack, by local number
     * @return the unsupported-number and unsupported-quote flags, in text order
     */
    public List<ClaimFlag> check(CitationValidator.Result result, List<EvidencePassage> passages) {
        Map<Integer, Set<String>> numbers = new HashMap<Integer, Set<String>>();
        Map<Integer, String> normalised = new HashMap<Integer, String>();
        for (EvidencePassage passage : passages) {
            numbers.put(passage.getNumber(), numbersOf(passage.getText()));
            normalised.put(passage.getNumber(), normaliseText(passage.getText()));
        }
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        List<CitationValidator.Paragraph> paragraphs = result.getParagraphs();
        for (int p = 0; p < paragraphs.size(); p++) {
            CitationValidator.Paragraph paragraph = paragraphs.get(p);
            for (CitationValidator.Sentence sentence : paragraph.getSentences()) {
                Set<Integer> cited = new LinkedHashSet<Integer>(sentence.getCited());
                cited.addAll(paragraph.getCited());
                String plain = sentence.plainText();
                Matcher m = NUMBER.matcher(plain);
                while (m.find()) {
                    if (!supported(readings(m.group()), cited, numbers)) {
                        flags.add(new ClaimFlag(ClaimFlag.Kind.UNSUPPORTED_NUMBER, p, sentence.getText(),
                                numberWithUnit(plain, m)));
                    }
                }
                for (String quote : quotesOf(plain)) {
                    String needle = normaliseText(quote);
                    if (needle.split(" ").length < MIN_QUOTE_WORDS) {
                        continue;
                    }
                    boolean found = false;
                    for (Integer n : cited) {
                        String hay = normalised.get(n);
                        if (hay != null && (" " + hay + " ").contains(" " + needle + " ")) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        flags.add(new ClaimFlag(ClaimFlag.Kind.UNSUPPORTED_QUOTE, p, sentence.getText(), quote));
                    }
                }
            }
        }
        return flags;
    }

    private static boolean supported(Set<String> readings, Set<Integer> cited, Map<Integer, Set<String>> numbers) {
        for (Integer n : cited) {
            Set<String> known = numbers.get(n);
            if (known == null) {
                continue;
            }
            for (String reading : readings) {
                if (known.contains(reading)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every reading of every number token of a text. */
    static Set<String> numbersOf(String text) {
        Set<String> all = new LinkedHashSet<String>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            all.addAll(readings(m.group()));
        }
        return all;
    }

    /**
     * The canonical values a number token can mean. "15,5" and "15.5" both read 15.5; "1.500" and "1,500" read
     * 1500 or 1.5, because one separator before exactly three digits is ambiguous between German and English;
     * "1.234,5" reads 1234.5.
     */
    static Set<String> readings(String token) {
        Set<String> out = new LinkedHashSet<String>();
        String t = token.replaceAll("[\u00A0\u202F\u2009']", "");
        int dots = count(t, '.');
        int commas = count(t, ',');
        if (dots == 0 && commas == 0) {
            out.add(canonical(t));
        } else if (dots > 0 && commas > 0) {
            char decimal = t.lastIndexOf('.') > t.lastIndexOf(',') ? '.' : ',';
            char thousands = decimal == '.' ? ',' : '.';
            out.add(canonical(t.replace(String.valueOf(thousands), "").replace(decimal, '.')));
        } else if (dots + commas > 1) {
            out.add(canonical(t.replaceAll("[.,]", "")));
        } else {
            int at = Math.max(t.indexOf('.'), t.indexOf(','));
            String tail = t.substring(at + 1);
            out.add(canonical(t.substring(0, at) + "." + tail));
            if (tail.length() == 3) {
                out.add(canonical(t.substring(0, at) + tail));
            }
        }
        out.remove("");
        return out;
    }

    private static String canonical(String plain) {
        try {
            BigDecimal value = new BigDecimal(plain);
            return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** The token with a directly following percent sign or short unit word, for a readable flag. */
    private static String numberWithUnit(String sentence, Matcher m) {
        Matcher unit = Pattern.compile("\\G\\s?(%|\\p{L}{1,12})").matcher(sentence);
        if (unit.find(m.end())) {
            return m.group() + (unit.group().startsWith(" ") ? " " : "") + unit.group(1);
        }
        return m.group();
    }

    static List<String> quotesOf(String sentence) {
        List<String> quotes = new ArrayList<String>();
        String rest = sentence;
        for (Pattern pattern : QUOTES) {
            Matcher m = pattern.matcher(rest);
            StringBuffer remaining = new StringBuffer();
            while (m.find()) {
                quotes.add(m.group(1).trim());
                m.appendReplacement(remaining, " ");
            }
            m.appendTail(remaining);
            rest = remaining.toString();
        }
        return quotes;
    }

    /** Lower case, accents kept, every run of non-letters and non-digits one space; quote styles do not matter. */
    static String normaliseText(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
    }
}
