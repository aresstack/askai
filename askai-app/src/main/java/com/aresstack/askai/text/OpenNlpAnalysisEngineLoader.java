package com.aresstack.askai.text;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;
import com.aresstack.askai.localmodels.LocalNlpModelStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The productive {@link ChatTextAnalysisService.EngineLoader}: loads whatever is INSTALLED in the
 * dedicated NLP store (Models → Setup → NLP) — the langdetect model for language identification
 * and the per-language sentence models. Everything is optional and degrades honestly: no
 * langdetect → no language detection ({@code languageDetector() == null}); no sentence model for a
 * language → a naive punctuation splitter. Detected languages are constrained to the read-aloud
 * pair de/en — anything else counts as "unknown" and stays in the fallback language.
 */
public final class OpenNlpAnalysisEngineLoader implements ChatTextAnalysisService.EngineLoader {

    private final LocalNlpModelStore store;

    public OpenNlpAnalysisEngineLoader() {
        this(new LocalNlpModelStore());
    }

    public OpenNlpAnalysisEngineLoader(LocalNlpModelStore store) {
        this.store = store;
    }

    @Override
    public ChatTextAnalysisService.Engine load() {
        opennlp.tools.langdetect.LanguageDetectorME detectorMe = null;
        // Sentence splitting is the knowledge pipeline's CANONICAL adapter (shared with the
        // source review) — never a second OpenNLP sentence implementation.
        final Map<String, com.aresstack.askai.research.text.opennlp.OpenNlpSentenceSegmenter>
                sentenceByLanguage = new HashMap<String,
                        com.aresstack.askai.research.text.opennlp.OpenNlpSentenceSegmenter>();
        for (NlpModelDescriptor descriptor : store.listInstalled()) {
            try {
                if (descriptor.getCapability() == NlpCapability.LANGUAGE_DETECTION
                        && detectorMe == null) {
                    detectorMe = new opennlp.tools.langdetect.LanguageDetectorME(
                            new opennlp.tools.langdetect.LanguageDetectorModel(
                                    new File(descriptor.getArtifactPath())));
                } else if (descriptor.getCapability() == NlpCapability.SENTENCE_DETECTION) {
                    sentenceByLanguage.put(descriptor.getLanguageCode(),
                            new com.aresstack.askai.research.text.opennlp.OpenNlpSentenceSegmenter(
                                    new File(descriptor.getArtifactPath())));
                }
            } catch (Exception unloadable) {
                System.err.println("[text-analysis] model " + descriptor.getModelId()
                        + " could not be loaded: " + unloadable.getMessage());
            }
        }
        final opennlp.tools.langdetect.LanguageDetectorME detector = detectorMe;
        return new ChatTextAnalysisService.Engine() {
            public ChatTextAnalysisService.LanguageDetector languageDetector() {
                if (detector == null) {
                    return null;
                }
                return new ChatTextAnalysisService.LanguageDetector() {
                    public String detectLanguage(String sentence) {
                        // Short fragments are unreliable — leave them in the fallback language.
                        if (sentence == null || sentence.trim().length() < 12) {
                            return "";
                        }
                        String iso3 = detector.predictLanguage(sentence).getLang();
                        if ("deu".equals(iso3)) {
                            return "de";
                        }
                        if ("eng".equals(iso3)) {
                            return "en";
                        }
                        return ""; // outside the read-aloud pair → fallback language
                    }
                };
            }

            public com.aresstack.askai.research.knowledge.SentenceSegmentationPort splitterFor(
                    String languageCode) {
                com.aresstack.askai.research.text.opennlp.OpenNlpSentenceSegmenter splitter =
                        sentenceByLanguage.get(languageCode);
                return splitter != null ? splitter : NAIVE_SPLITTER;
            }

            public void close() {
                sentenceByLanguage.clear(); // the models become collectable; nothing holds files
            }
        };
    }

    /** Sentence-ish split on terminal punctuation — the model-less fallback. */
    static final com.aresstack.askai.research.knowledge.SentenceSegmentationPort NAIVE_SPLITTER =
            new com.aresstack.askai.research.knowledge.SentenceSegmentationPort() {
                private final Pattern boundary = Pattern.compile("(?<=[.!?])\\s+");

                public List<String> segment(String text) {
                    List<String> sentences = new ArrayList<String>();
                    for (String sentence : boundary.split(text == null ? "" : text)) {
                        if (!sentence.trim().isEmpty()) {
                            sentences.add(sentence.trim());
                        }
                    }
                    return sentences;
                }
            };
}
