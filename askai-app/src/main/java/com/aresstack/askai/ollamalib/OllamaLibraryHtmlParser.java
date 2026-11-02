package com.aresstack.askai.ollamalib;

import jodd.jerry.Jerry;
import jodd.jerry.JerryFunction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ollama.com's server-rendered search and library pages with Jerry/Lagarto. Selection is by
 * stable, class-name-independent signals — the {@code /library/} href prefixes and the visible text
 * — because ollama.com's Tailwind class names change often while the structure/hrefs do not.
 *
 * <p>When a non-trivial page yields no results the parser throws a clear "HTML changed" error rather
 * than returning an empty list, so scraper drift is visible instead of looking like "no matches".</p>
 */
public final class OllamaLibraryHtmlParser {

    private static final String LIBRARY_PREFIX = "/library/";
    private static final Pattern PARAM_SIZE = Pattern.compile("(?i)^\\d+(?:\\.\\d+)?x?\\d*b$");
    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z-]{2,15}$");
    private static final Pattern PULLS = Pattern.compile("([\\d.,]+[KMB]?)\\s*Pulls");
    private static final Pattern TAGS = Pattern.compile("([\\d.,]+[KMB]?)\\s*Tags");
    private static final Pattern UPDATED = Pattern.compile("Updated\\s+(.+?)\\s*$");
    private static final Pattern SIZE = Pattern.compile("(?i)^\\d+(?:\\.\\d+)?(?:gb|mb|kb|tb|b)$");

    /** Parses a {@code /search?q=} page into its result models. */
    public List<OllamaLibraryModel> parseSearchResults(String html) throws IOException {
        final List<OllamaLibraryModel> models = new ArrayList<OllamaLibraryModel>();
        Jerry doc = Jerry.of(html == null ? "" : html);
        doc.find("a[href^=\"" + LIBRARY_PREFIX + "\"]").each(new JerryFunction() {
            public Boolean onNode(Jerry node, int index) {
                String href = node.attr("href");
                if (href == null) {
                    return true;
                }
                String base = href.substring(LIBRARY_PREFIX.length());
                // Only real model results: no tag (":"), no sub-path, and a heading present.
                if (base.length() == 0 || base.indexOf(':') >= 0 || base.indexOf('/') >= 0) {
                    return true;
                }
                Jerry heading = node.find("h2");
                if (heading.size() == 0) {
                    return true;
                }
                models.add(buildSearchModel(base, node));
                return true;
            }
        });
        if (models.isEmpty() && html != null && html.length() > 200) {
            throw new IOException("Ollama-Suchseite hat sich geändert (keine Treffer im HTML gefunden) — "
                    + "der Scraper muss angepasst werden.");
        }
        return models;
    }

    private OllamaLibraryModel buildSearchModel(String base, Jerry node) {
        String description = node.find("p").size() > 0 ? node.find("p").first().text().trim() : "";
        List<String> capabilities = new ArrayList<String>();
        List<String> parameterSizes = new ArrayList<String>();
        classifyBadges(node, capabilities, parameterSizes);
        String blockText = normalize(node.text());
        return new OllamaLibraryModel(base, description, capabilities, parameterSizes,
                firstGroup(PULLS, blockText), parseCount(firstGroup(TAGS, blockText)), firstGroup(UPDATED, blockText));
    }

    /** Splits the result's short badge spans into capabilities (words) and parameter sizes (…b). */
    private void classifyBadges(Jerry node, final List<String> capabilities, final List<String> parameterSizes) {
        node.find("span").each(new JerryFunction() {
            public Boolean onNode(Jerry span, int index) {
                String text = span.text().trim();
                if (text.length() == 0) {
                    return true;
                }
                String lower = text.toLowerCase(Locale.ROOT);
                if (PARAM_SIZE.matcher(lower).matches()) {
                    if (!parameterSizes.contains(text)) {
                        parameterSizes.add(text);
                    }
                } else if (CAPABILITY.matcher(lower).matches() && !isStatLabel(lower) && !capabilities.contains(lower)) {
                    capabilities.add(lower);
                }
                return true;
            }
        });
    }

    private static boolean isStatLabel(String lower) {
        return lower.equals("pulls") || lower.equals("tags") || lower.equals("updated");
    }

    /**
     * Parses a {@code /library/<baseName>} page into its installable tag variants.
     *
     * <p>The canonical pull name is taken from the hidden {@code <input class="command" value="…">}
     * when present (the value ollama.com's copy button uses); otherwise it falls back to the
     * {@code /library/<name>:<tag>} anchors, since the server-rendered HTML does not always include
     * the input. Size / context / input types / updated / latest come from the same variant's detail
     * row (the mobile row carries them all in one "·"-separated line). ollama.com ships each variant
     * as both a mobile and a desktop row, so tags are de-duplicated.</p>
     */
    public List<OllamaModelVariant> parseModelVariants(final String baseName, String html) throws IOException {
        Jerry doc = Jerry.of(html == null ? "" : html);

        // Detail rows (mobile <a>): the ones whose text carries the "·"-separated detail line.
        final Map<String, VariantDetail> details = new LinkedHashMap<String, VariantDetail>();
        doc.find("a[href^=\"" + LIBRARY_PREFIX + baseName + ":\"]").each(new JerryFunction() {
            public Boolean onNode(Jerry node, int index) {
                String href = node.attr("href");
                if (href == null) {
                    return true;
                }
                String tag = href.substring(LIBRARY_PREFIX.length());
                String text = normalize(node.text());
                if (text.indexOf('·') < 0) {
                    return true; // desktop inner anchor (no detail line) — skip; the mobile row has it
                }
                if (!details.containsKey(tag)) {
                    details.put(tag, buildDetail(node, text));
                }
                return true;
            }
        });

        // Canonical, de-duplicated tag order: prefer input.command values, else the detail rows.
        final List<String> tags = new ArrayList<String>();
        doc.find("input.command").each(new JerryFunction() {
            public Boolean onNode(Jerry node, int index) {
                String value = node.attr("value");
                if (value != null && value.startsWith(baseName + ":") && !tags.contains(value)) {
                    tags.add(value);
                }
                return true;
            }
        });
        if (tags.isEmpty()) {
            tags.addAll(details.keySet());
        }

        List<OllamaModelVariant> variants = new ArrayList<OllamaModelVariant>();
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            VariantDetail detail = details.get(tag);
            if (detail == null) {
                detail = new VariantDetail("", "", new ArrayList<String>(), "", false);
            }
            variants.add(new OllamaModelVariant(tag, detail.size, detail.context, detail.inputs,
                    detail.updated, detail.latest));
        }
        if (variants.isEmpty() && html != null && html.length() > 200) {
            throw new IOException("Ollama-Modellseite hat sich geändert (keine Varianten im HTML gefunden) — "
                    + "der Scraper muss angepasst werden.");
        }
        return variants;
    }

    /** Parses one variant's detail row: "… 15GB · 384K context window · Text, Image · 7 months ago". */
    private VariantDetail buildDetail(Jerry node, String text) {
        String[] parts = text.split("\\u00b7");
        // The token before the first "·" is the on-disk size — but cloud tags have no local size, so
        // only accept it when it actually looks like one (e.g. "15GB"), else leave it blank.
        String sizeToken = parts.length >= 1 ? lastToken(parts[0]) : "";
        String size = SIZE.matcher(sizeToken).matches() ? sizeToken : "";
        String context = parts.length > 1 ? parts[1].trim() : "";
        List<String> inputs = splitInputs(parts.length > 2 ? parts[2].trim() : "");
        String updated = parts.length > 3 ? parts[3].trim() : "";
        boolean latest = hasLatestBadge(node);
        return new VariantDetail(size, context, inputs, updated, latest);
    }

    /** @return true when the row carries a standalone "latest" badge (not the tag name itself). */
    private static boolean hasLatestBadge(Jerry node) {
        final boolean[] found = {false};
        node.find("span").each(new JerryFunction() {
            public Boolean onNode(Jerry span, int index) {
                if ("latest".equalsIgnoreCase(span.text().trim())) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static List<String> splitInputs(String value) {
        List<String> inputs = new ArrayList<String>();
        if (value == null || value.length() == 0) {
            return inputs;
        }
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            String input = parts[i].trim();
            if (input.length() > 0) {
                inputs.add(input);
            }
        }
        return inputs;
    }

    /** @return the last whitespace-separated token of a string, e.g. "…24b latest 15GB" → "15GB". */
    private static String lastToken(String text) {
        String trimmed = text.trim();
        int space = trimmed.lastIndexOf(' ');
        return space >= 0 ? trimmed.substring(space + 1) : trimmed;
    }

    private static final class VariantDetail {
        private final String size;
        private final String context;
        private final List<String> inputs;
        private final String updated;
        private final boolean latest;

        private VariantDetail(String size, String context, List<String> inputs, String updated, boolean latest) {
            this.size = size;
            this.context = context;
            this.inputs = inputs;
            this.updated = updated;
            this.latest = latest;
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static int parseCount(String text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(text.replace(",", "").replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }
}
