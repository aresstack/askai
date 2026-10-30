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
 *   <li>search offers and an exclusion still run through their commands.</li>
 * </ol>
 * Skips itself without a local Ollama on 127.0.0.1:11434 or without the model. The full app path (plugin
 * concept service, persisted Konzeptpapier, research scope) is checked separately over the AskAI test MCP
 * server.
 */
public class ScopingConceptLiveTest {

    private static final String BASE_URL = "http://127.0.0.1:11434";
    private static final String MODEL = System.getProperty("askai.live.main.model", "gemma4:e2b");
    private static final String[] FOREIGN = {"esp32", "esp-idf", "freertos", "arduino", "praxisprojekt"};

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

    @Test
    public void hasenhaltungGetsNoForeignCardsAndNoLeakedActions() {
        Chat chat = new Chat("hasen");
        chat.say("hasenhaltung");
        chat.report();
        for (String card : chat.host.cards) {
            String lower = card.toLowerCase(Locale.ROOT);
            for (String foreign : FOREIGN) {
                assertFalse("foreign card from the prompt examples: " + card, lower.contains(foreign));
            }
            assertFalse("placeholder became a card: " + card, card.contains("<"));
        }
        chat.assertNoLeaks();
    }

    @Test
    public void meerschweinchenCardsAreExecutedAndAnExclusionStillRuns() {
        Chat chat = new Chat("meerschweinchen");
        chat.say("Ich möchte mich über artgerechte Haltung und Ernährung von Meerschweinchen informieren.");
        assertFalse("cards must be executed through conceptAction, not only described: " + chat.host.calls,
                chat.host.cards.isEmpty());
        chat.say("Zucht möchte ich nicht behandeln.");
        chat.report();
        chat.assertNoLeaks();
        boolean excluded = false;
        for (String call : chat.host.calls) {
            excluded |= call.startsWith("exclude") && call.toLowerCase(Locale.ROOT).contains("zucht");
        }
        assertTrue("the exclusion command still runs: " + chat.host.calls, excluded);
    }

    @Test
    public void aSecondChatSeesNothingOfTheFirstChatsTopic() {
        Chat first = new Chat("first");
        first.say("hasenhaltung");
        Chat second = new Chat("second");
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

        Chat(String name) {
            this.name = name;
        }

        void say(String userText) {
            TeamAgentResult result = ConceptToolRounds.run(agent.respond(userText, view),
                    feedback -> agent.internalTurn(feedback, view), host,
                    ConceptToolRounds.DEFAULT_MAX_TOOL_ROUNDS, ConceptToolRounds.DEFAULT_MAX_REPAIR_ATTEMPTS,
                    true, null, line -> trace.add(line), !offered,
                    ConceptTurnPolicy.modeFor(userText), false, null);
            assertTrue("turn failed: " + result.getStatus() + " " + result.getDetail(), result.isOk());
            offered |= host.offers > 0;
            visible.add(result.getOutput().getAssistantMessage());
        }

        void assertNoLeaks() {
            for (String message : visible) {
                assertNull("visible answer spells an internal action: " + message,
                        VisibleAssistantMessageValidator.actionLeakIn(message));
            }
        }

        void report() {
            System.err.println("[scoping-live " + name + "] model=" + MODEL);
            for (String line : trace) {
                System.err.println("[scoping-live " + name + "] concept " + line);
            }
            System.err.println("[scoping-live " + name + "] cards=" + host.cards + " offers=" + host.offers);
            for (String message : visible) {
                System.err.println("[scoping-live " + name + "] visible: " + message.replace('\n', ' '));
            }
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
                        receipt.append("ADDED \"").append(name).append("\"\n");
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
