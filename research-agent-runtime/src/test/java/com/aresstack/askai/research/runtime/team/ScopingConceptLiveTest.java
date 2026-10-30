package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.java8.modelport.CentralModelDirectories;
import com.aresstack.askai.java8.modelport.OllamaModelPort;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.role.ResearchRoles;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The scoping regression's LIVE GATE against a real local model (default {@code gemma4:e2b}, override with
 * {@code -Daskai.live.main.model=...}): the real scoping prompt and grammar over the real model port
 * (ollama4j), the real {@link ConceptToolRounds} loop, and an in-memory concept host that answers like the
 * plugin's concept tools. It covers the model side of the reported failures:
 * <ol>
 *   <li>"hasenhaltung" yields no cards from the prompt's former examples (ESP32/FreeRTOS/Arduino) and no
 *       placeholders;</li>
 *   <li>a guinea-pig request yields cards that are actually executed through conceptAction;</li>
 *   <li>no visible answer spells an internal action (add_cards/offer/none);</li>
 *   <li>a second chat sees nothing of the first chat's topic;</li>
 *   <li>search offers and an exclusion still run through their commands;</li>
 *   <li>the conversation ends cleanly: no host fallback answer, no unanswered nudge.</li>
 * </ol>
 * Skips itself without a local Ollama on 127.0.0.1:11434 or without the model. The full app path (plugin
 * concept service, persisted Konzeptpapier, research scope) is checked separately over the AskAI test MCP
 * server.
 */
public class ScopingConceptLiveTest {

    private static final String BASE_URL = "http://127.0.0.1:11434";
    private static final String MODEL = System.getProperty("askai.live.main.model", "gemma4:e2b");
    private static final String[] FOREIGN = {"esp32", "esp-idf", "freertos", "arduino", "praxisprojekt"};
    /** Repeated runs per case ({@code -Daskai.live.repeat=N}): a passing case must pass every time. */
    private static final int REPEAT = Integer.getInteger("askai.live.repeat", 1);

    private static OllamaModelPort port;

    @BeforeClass
    public static void requireModel() {
        Assume.assumeTrue("no local Ollama with " + MODEL, modelInstalled(MODEL));
        port = CentralModelDirectories.fixedModelPort(BASE_URL, MODEL, Duration.ofMinutes(5));
    }

    @AfterClass
    public static void closePort() {
        if (port != null) {
            port.close();
        }
    }

    /** Words of which at least one card of a fitting rabbit-keeping concept names. */
    private static final String[] RABBIT_WORDS = {"hase", "kaninchen", "haltung", "gehege", "stall", "auslauf",
            "ernährung", "futter", "fütterung", "gesundheit", "pflege", "tierarzt", "unterbringung", "verhalten",
            "sozial", "artgerecht", "krankheit", "impf", "beschäftigung", "einrichtung"};
    /** Words of which at least one card of a fitting guinea-pig concept names. */
    private static final String[] GUINEA_PIG_WORDS = {"meerschwein", "haltung", "gehege", "stall", "käfig",
            "auslauf", "ernährung", "futter", "fütterung", "heu", "vitamin", "gesundheit", "pflege", "tierarzt",
            "unterbringung", "verhalten", "sozial", "artgerecht", "gruppe", "krankheit", "einrichtung"};

    @Test
    public void hasenhaltungBuildsFittingCardsWithoutForeignOnesOrPlaceholders() {
        for (int run = 1; run <= REPEAT; run++) {
            hasenhaltung(new Chat("hasen#" + run));
        }
    }

    private static void hasenhaltung(Chat chat) {
        chat.say("hasenhaltung");
        chat.report();
        assertFalse("the first turn must build cards through conceptAction: " + chat.host.calls,
                chat.host.cards.isEmpty());
        for (String card : chat.host.cards) {
            String lower = card.toLowerCase(Locale.ROOT);
            for (String foreign : FOREIGN) {
                assertFalse("foreign card from the prompt examples: " + card, lower.contains(foreign));
            }
        }
        chat.assertCardsFit(RABBIT_WORDS);
        assertTrue("the first turn offers search tags through conceptAction: " + chat.host.calls,
                chat.host.offers > 0);
        chat.assertNoPlaceholderCards();
        chat.assertCleanConversation();
    }

    @Test
    public void meerschweinchenCardsAreStoredAndAnExclusionStillRuns() {
        for (int run = 1; run <= REPEAT; run++) {
            meerschweinchen(new Chat("meerschweinchen#" + run));
        }
    }

    private static void meerschweinchen(Chat chat) {
        chat.say("Ich möchte mich über artgerechte Haltung und Ernährung von Meerschweinchen informieren.");
        assertFalse("cards must be executed through conceptAction, not only described: " + chat.host.calls,
                chat.host.cards.isEmpty());
        chat.assertCardsFit(GUINEA_PIG_WORDS);
        assertTrue("the first turn offers search tags through conceptAction: " + chat.host.calls,
                chat.host.offers > 0);
        chat.say("Zucht möchte ich nicht behandeln.");
        chat.report();
        chat.assertNoPlaceholderCards();
        chat.assertCleanConversation();
        boolean excluded = false;
        for (String call : chat.host.calls) {
            excluded |= call.startsWith("exclude") && call.toLowerCase(Locale.ROOT).contains("zucht");
        }
        assertTrue("the exclusion command still runs: " + chat.host.calls, excluded);
    }

    @Test
    public void aSecondChatSeesNothingOfTheFirstChatsTopic() {
        for (int run = 1; run <= REPEAT; run++) {
            secondChat(run);
        }
    }

    private static void secondChat(int run) {
        Chat first = new Chat("first#" + run);
        first.say("hasenhaltung");
        Chat second = new Chat("second#" + run);
        second.say("Ich möchte mich über artgerechte Haltung und Ernährung von Meerschweinchen informieren.");
        second.report();
        for (List<ChatMessage> request : second.recording.requests) {
            for (ChatMessage message : request) {
                assertFalse("the second chat's context carries the first chat's topic",
                        message.getContent().toLowerCase(Locale.ROOT).contains("hasenhaltung"));
            }
        }
        for (String card : second.host.cards) {
            assertFalse("a card of the first chat: " + card, card.toLowerCase(Locale.ROOT).contains("hase"));
        }
        assertFalse("the second chat builds its own cards: " + second.host.calls, second.host.cards.isEmpty());
        second.assertNoPlaceholderCards();
    }

    // ------------------------------------------------------------------ one chat

    /** One research chat: its own TeamAgent, its own concept host, its own recorded model context. */
    private static final class Chat {
        final String name;
        final RecordingChat recording = new RecordingChat(PortMainModelChat.forRole(port,
                ResearchRoles.CONVERSATION, Duration.ofMinutes(5)));
        final ResearchTeamAgent agent = new ResearchTeamAgent(recording,
                PhaseAssistantProfileRegistry.defaults(true, true), new PhaseContextAssembler());
        final InMemoryConceptHost host = new InMemoryConceptHost();
        final List<String> visible = new ArrayList<String>();
        final List<String> trace = new ArrayList<String>();
        final TeamAgentStateView view = new TeamAgentStateView("scoping", "running",
                Collections.<String>emptyList());
        boolean offered;
        int printed;

        Chat(String name) {
            this.name = name;
        }

        void say(String userText) {
            TeamAgentResult result = ConceptToolRounds.run(agent.respond(userText, view),
                    new ConceptToolRounds.FollowUpTurn() {
                        public TeamAgentResult run(String feedback) {
                            return agent.internalTurn(feedback, view);
                        }

                        public TeamAgentResult run(String feedback, String requiredActionType) {
                            return agent.internalTurn(feedback, view, requiredActionType);
                        }
                    }, host,
                    ConceptToolRounds.DEFAULT_MAX_TOOL_ROUNDS, ConceptToolRounds.DEFAULT_MAX_REPAIR_ATTEMPTS,
                    true, null, line -> trace.add(line), !offered,
                    ConceptTurnPolicy.modeFor(userText), false, null, userText);
            for (String line : trace.subList(printed, trace.size())) {
                System.err.println("[scoping-live " + name + "] concept " + line);
            }
            printed = trace.size();
            assertTrue("turn failed: " + result.getStatus() + " " + result.getDetail(), result.isOk());
            offered |= host.offers > 0;
            visible.add(result.getOutput().getAssistantMessage());
            System.err.println("[scoping-live " + name + "] visible: "
                    + result.getOutput().getAssistantMessage().replace('\n', ' '));
        }

        /** At least one card names the topic's own areas (a loose word list — no exact model output). */
        void assertCardsFit(String[] topicWords) {
            for (String card : host.cards) {
                String lower = card.toLowerCase(Locale.ROOT);
                for (String word : topicWords) {
                    if (lower.contains(word)) {
                        return;
                    }
                }
            }
            throw new AssertionError("no card names an area of the topic: " + host.cards);
        }

        /** No stored card is a placeholder ("<…>", "...", "…") or empty. */
        void assertNoPlaceholderCards() {
            for (String card : host.cards) {
                String trimmed = card.trim();
                assertFalse("placeholder became a card: " + card, trimmed.isEmpty() || trimmed.contains("<")
                        || trimmed.contains(">") || trimmed.contains("...") || trimmed.contains("…"));
            }
        }

        /**
         * The conversation ends cleanly: no visible answer spells an internal action, none is the host's
         * fallback for an unexecuted action, and the last answer is a real reply to the user.
         */
        void assertCleanConversation() {
            for (String message : visible) {
                assertNull("visible answer spells an internal action: " + message,
                        VisibleAssistantMessageValidator.actionLeakIn(message));
            }
            for (String line : trace) {
                assertFalse("the host had to replace a leaked answer: " + line,
                        line.contains("host answer replaces"));
            }
            String last = visible.get(visible.size() - 1).trim();
            assertTrue("the last answer is a real reply: " + last, last.length() >= 20);
        }

        void report() {
            System.err.println("[scoping-live " + name + "] model=" + MODEL + " cards=" + host.cards
                    + " offers=" + host.offers + " calls=" + host.calls);
        }
    }

    /** Records every model context of one chat. */
    private static final class RecordingChat implements MainModelChat {
        final MainModelChat delegate;
        final List<List<ChatMessage>> requests = new ArrayList<List<ChatMessage>>();

        RecordingChat(MainModelChat delegate) {
            this.delegate = delegate;
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            requests.add(new ArrayList<ChatMessage>(messages));
            return delegate.complete(messages, temperature, maxOutputTokens);
        }

        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                                int maxOutputTokens, String schemaJson) {
            requests.add(new ArrayList<ChatMessage>(messages));
            return delegate.completeJson(messages, temperature, maxOutputTokens, schemaJson);
        }

        public String modelName() {
            return delegate.modelName();
        }
    }

    /** Answers like the plugin's concept tools (receipts with revisions), keeping the cards in memory. */
    private static final class InMemoryConceptHost implements ConceptToolRounds.ConceptTool {
        final Map<String, List<String>> tree = new LinkedHashMap<String, List<String>>();
        final List<String> cards = new ArrayList<String>();
        final List<String> calls = new ArrayList<String>();
        int revision;
        int offers;

        public String call(ConceptAction action) throws ToolInvoker.ToolFailure {
            calls.add(action.describe());
            switch (action.getType()) {
                case READ:
                    return conceptJson();
                case ADD_CARDS: {
                    List<String> names = names(action.getNamesJson());
                    String parent = action.getParent().isEmpty() ? ""
                            : action.getParent().get(action.getParent().size() - 1);
                    StringBuilder receipt = new StringBuilder("APPLIED revision=" + (++revision) + "\n");
                    for (String name : names) {
                        cards.add(name);
                        tree.computeIfAbsent(parent, key -> new ArrayList<String>()).add(name);
                        receipt.append("ADDED: ").append(name).append("\n");
                    }
                    return receipt.toString();
                }
                case RENAME:
                    return "APPLIED revision=" + (++revision);
                case OFFER:
                    offers++;
                    return "OFFERED " + action.getSuggestionsJson();
                case EXCLUDE:
                    return "{\"result\":\"EXCLUDED\",\"userMessage\":\"„" + action.getTopic()
                            + "“ ist jetzt von der Recherche ausgeschlossen.\"}";
                default:
                    throw new ToolInvoker.ToolFailure("not supported by the live gate host: "
                            + action.describe());
            }
        }

        private String conceptJson() {
            StringBuilder sb = new StringBuilder("{\"concept\":[");
            List<String> roots = tree.containsKey("") ? tree.get("") : Collections.<String>emptyList();
            if (roots.isEmpty()) {
                return "{\"concept\":[]}";
            }
            for (int i = 0; i < roots.size(); i++) {
                sb.append(i > 0 ? "," : "").append("{\"").append(roots.get(i)).append("\":[");
                List<String> children = tree.containsKey(roots.get(i)) ? tree.get(roots.get(i))
                        : Collections.<String>emptyList();
                for (int j = 0; j < children.size(); j++) {
                    sb.append(j > 0 ? "," : "").append('"').append(children.get(j)).append('"');
                }
                sb.append("]}");
            }
            return sb.append("],\"revision\":").append(revision).append('}').toString();
        }

        @SuppressWarnings("unchecked")
        private static List<String> names(String json) {
            List<String> names = new ArrayList<String>();
            Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(json);
            if (parsed instanceof List) {
                for (Object name : (List<Object>) parsed) {
                    names.add(String.valueOf(name));
                }
            }
            return names;
        }
    }

    private static boolean modelInstalled(String model) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + "/api/tags").openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            InputStream in = connection.getInputStream();
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();
            return body.contains("\"" + model + "\"");
        } catch (Exception unreachable) {
            return false;
        }
    }
}
