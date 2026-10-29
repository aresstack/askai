package com.aresstack.askai.research.agent;

import java.util.Locale;

/**
 * The research agent's PLAYBOOK: the stable knowledge about its role, method and limits, phrased for the
 * USER. It backs the consultative dialog (greeting with an open question, paraphrase, focused follow-ups,
 * "anything missing?", proposing the approval) and the agent's ability to EXPLAIN itself on demand. Internal
 * command or phase identifiers never appear in these texts. This is also the stable-context half that a
 * future LLM binding receives as system knowledge — the seam stays, only the wording generator changes.
 */
public final class ResearchPlaybook {

    private final ResearchLanguageProvider language;

    /**
     * The playbook reads the CURRENT session language on every utterance, so a live switch applies to the
     * next generated text — it never copies the language at construction time.
     */
    public ResearchPlaybook(ResearchLanguageProvider language) {
        this.language = language == null
                ? new SessionResearchLanguage(ResearchLanguage.ENGLISH)
                : language;
    }

    /** Convenience for callers with a FIXED language (settings previews, tests). */
    public ResearchPlaybook(ResearchLanguage fixedLanguage) {
        this(new SessionResearchLanguage(fixedLanguage));
    }

    /** The live language check narrators and the session share for their own bilingual texts. */
    public boolean isGerman() {
        return language.currentLanguage() == ResearchLanguage.GERMAN;
    }

    private boolean de() {
        return isGerman();
    }

    /** First contact: friendly, takes initiative, ONE open question — never an approval. */
    /** A failed scope commit: explain, keep the dialog repeatable — never a silent advance. */
    public String scopeCommitFailed(String detail) {
        return de()
                ? "Der Forschungsauftrag konnte nicht gespeichert werden (" + detail
                        + "). Es wurde nichts fortgeschaltet - bitte bestaetige den Umfang erneut."
                : "The research scope could not be saved (" + detail
                        + "). Nothing was advanced - please confirm the scope again.";
    }

    public String greeting() {
        if (de()) {
            return "Hallo! Ich unterstütze dich bei einer strukturierten Recherche: Wir klären zuerst, "
                    + "WAS du herausfinden willst, dann schlage ich dir eine Gliederung zur Freigabe "
                    + "vor, und danach recherchiere ich echte Webquellen und sammle die Belege für "
                    + "dich.\n\n"
                    + "Also: Was möchtest du herausfinden?";
        }
        return "Hi! I help you run a structured research: we first clarify WHAT you want to find out, "
                + "then I propose an outline for your approval, and after that I research real web "
                + "sources and collect the evidence for you.\n\n"
                + "So: what would you like to find out?";
    }

    /** Echo-based paraphrase + ONE focused follow-up (honest: mirrors, does not pretend deep analysis). */
    public String paraphraseAndFocus(String question) {
        if (de()) {
            return "Verstanden — du möchtest recherchieren:\n\n> " + question + "\n\n"
                    + "Eine fokussierende Frage: Gibt es bestimmte Aspekte, die dir besonders wichtig "
                    + "sind (zum Beispiel Architektur, Updates, Sicherheit, Alternativen)? "
                    + "Du kannst auch einfach \"start\" sagen, dann arbeite ich mit dem, was wir haben.";
        }
        return "Got it — you want to research:\n\n> " + question + "\n\n"
                + "One focusing question: are there specific aspects that matter most to you "
                + "(for example architecture, updates, security, alternatives)? "
                + "You can also just say \"start\" and I will work with what we have.";
    }

    /** Scope summary + the explicit missing-anything check. */
    public String summarizeAndCheck(String question, java.util.List<String> aspects) {
        StringBuilder sb = new StringBuilder(de()
                ? "So verstehe ich den Umfang bisher:\n\n"
                : "Here is my current understanding of the scope:\n\n");
        sb.append(de() ? "- Forschungsfrage: " : "- Research question: ").append(question).append('\n');
        for (String aspect : aspects) {
            sb.append(de() ? "- Schwerpunkt: " : "- Focus: ").append(aspect).append('\n');
        }
        sb.append(de()
                ? "\nFehlt noch etwas Wichtiges? Wenn alles vollständig ist, sage einfach \"nein\" "
                        + "(oder \"start\"), dann schlage ich die Gliederung vor."
                : "\nIs anything important missing? If it is complete, just say \"no\" (or \"start\") "
                        + "and I will propose the outline.");
        return sb.toString();
    }

    /** True when the user closes the scoping ("no, that's it" / "start" / "go ahead"). */
    public static boolean isConfirmation(String text) {
        String t = normalize(text);
        return t.equals("no") || t.equals("nein") || t.startsWith("no,") || t.startsWith("no ")
                || t.startsWith("nein,") || t.contains("nothing missing") || t.contains("passt")
                || t.contains("that's it") || t.contains("thats it") || isStartRequest(text);
    }

    /** True when the user wants to skip further questions entirely. */
    public static boolean isStartRequest(String text) {
        String t = normalize(text);
        return t.contains("start") || t.contains("go ahead") || t.contains("leg los")
                || t.contains("los geht") || t.equals("ja") || t.equals("yes");
    }

    /**
     * Explainability: a playbook answer for meta questions ("what can you do", "how does this work",
     * "why do you ask", "which phase", "what happens next"), or {@code null} for normal content.
     * Answers combine the stable playbook with the LIVE state description passed in.
     */
    public String explain(String text, String phaseDescription) {
        String t = normalize(text);
        boolean asksWhat = t.contains("what can you") || t.contains("was kannst du")
                || t.contains("what do you do") || t.contains("was machst du")
                || t.contains("how does this work") || t.contains("wie funktioniert");
        if (asksWhat) {
            if (de()) {
                return "Ich helfe dir zuerst, die Forschungsfrage und ihren Umfang zu schärfen. Danach "
                        + "erstelle ich eine Gliederung, die du prüfen kannst. Nach deiner Freigabe "
                        + "durchsuche ich echte Webquellen, sammle und dedupliziere Belege und lege dir "
                        + "die Ergebnisse zur Prüfung vor. Später kann daraus ein strukturierter Bericht "
                        + "entstehen.\n\nAktuell: " + phaseDescription;
            }
            return "I first help you sharpen the research question and its scope. Then I create an "
                    + "outline you can review. After your approval I browse real web sources, collect "
                    + "and deduplicate evidence, and present the findings for your review. Later this "
                    + "can grow into a structured report.\n\nRight now: " + phaseDescription;
        }
        if (t.contains("why do you ask") || t.contains("warum fragst du")
                || t.contains("why so many questions") || t.contains("so viele fragen")) {
            if (de()) {
                return "Damit ich nicht am eigentlichen Ziel vorbeirecherchiere — ich frage nur nach "
                        + "Punkten, die Suchrichtung, Quellenwahl oder Ergebnisform wesentlich "
                        + "beeinflussen.";
            }
            return "So that I do not research past your actual goal — I only ask about points that "
                    + "change the search direction, the choice of sources or the form of the result.";
        }
        if (t.contains("which phase") || t.contains("welche phase") || t.contains("what phase")
                || t.contains("what happens next") || t.contains("was passiert als")) {
            return phaseDescription;
        }
        return null;
    }

    /** Human wording for the current situation (no internal identifiers). */
    public String describePhase(String phaseId, String stateId, boolean hasQuestion) {
        if ("scoping".equals(phaseId) || !hasQuestion) {
            return de()
                    ? "Wir klären gerade, was du herausfinden möchtest. Als Nächstes fasse ich den "
                            + "Umfang zusammen und schlage dann eine Gliederung zur Freigabe vor."
                    : "We are clarifying what you want to find out. Next: I summarize the scope, then "
                            + "propose an outline for your approval.";
        }
        if ("outline".equals(phaseId)) {
            return de()
                    ? "Die Gliederung wartet auf deine Freigabe. Nach deiner Freigabe starte ich die "
                            + "Webrecherche automatisch."
                    : "The outline is waiting for your approval. After you approve it, I start the web "
                            + "research automatically.";
        }
        if ("research".equals(phaseId)) {
            return de()
                    ? "Ich recherchiere gerade Webquellen zu deiner Frage. Sobald die Belege "
                            + "ausreichen, bitte ich dich um eine Prüfung."
                    : "I am researching web sources for your question. When the evidence is sufficient, "
                            + "I will ask you to review it.";
        }
        if ("evidence".equals(phaseId)) {
            return de()
                    ? "Die gesammelten Belege warten auf deine Prüfung."
                    : "The collected evidence is waiting for your review.";
        }
        return (de() ? "Aktueller Schritt: " : "Current step: ") + phaseId + " (" + stateId + ").";
    }

    // ------------------------------------------------------------------ run progress card (one per run)

    /** Title of the single in-place progress card. */
    public String progressTitle() {
        return de() ? "Webrecherche läuft" : "Web research in progress";
    }

    /** The search the agent is running right now — the query is the user's own request, so it is shown. */
    public String progressSearchLine(String query) {
        return (de() ? "Suche im Web nach:\n" : "Searching the web for:\n") + "„" + query + "“";
    }

    /** REST-provider search: no browser is involved in this step, and the card says so. */
    public String progressApiSearchLine(String provider, String query) {
        return (de() ? "Suche per " + provider + " (ohne Browser) nach:\n"
                : "Searching via " + provider + " (no browser) for:\n") + "„" + query + "“";
    }

    /** Thought bubble while a REST provider answers the search — the browser stays closed here. */
    public String apiSearchThinking(String provider, String query) {
        return de()
                ? "fragt " + provider + " nach „" + query + "“ …"
                : "asking " + provider + " about “" + query + "” …";
    }

    /** Closes the REST-search thought bubble once the provider answered. */
    public String apiSearchDone(String provider) {
        return de()
                ? "Ergebnisse von " + provider + " erhalten."
                : "Results from " + provider + " received.";
    }

    /** The real target website the browser is on right now (final host + page title, never raw URLs). */
    public String progressPageLine(String host, String pageTitle) {
        String head = de() ? "Gerade geöffnet:\n" : "Currently open:\n";
        return head + host + (pageTitle == null || pageTitle.isEmpty() ? "" : "\n" + pageTitle);
    }

    /** Heading of the compact visible activity history (the last few processed websites). */
    public String recentPagesTitle() {
        return de() ? "Zuletzt:" : "Recently:";
    }

    /** One history entry for a page recorded as a source. */
    public String historyAccepted(String host, String pageTitle) {
        return "✓ " + host + (pageTitle == null || pageTitle.isEmpty() ? "" : " — " + pageTitle);
    }

    /** One history entry for a page checked and found not relevant. */
    public String historySkipped(String host) {
        return "– " + host + (de() ? " — nicht relevant" : " — not relevant");
    }

    /** Explains the headless switch: transparency comes from the chat, not from a visible browser window. */
    public String headlessHint() {
        return de()
                ? "Headless: Das Browserfenster bleibt verborgen. Die besuchten Websites werden "
                        + "während der Recherche im Chat angezeigt."
                : "Headless: the browser window stays hidden. The websites visited are shown in the "
                        + "chat while the research runs.";
    }

    /** Static title of the narration thought bubble while the LLM narrator phrases a milestone. */
    public String narratorThinking() {
        return de() ? "überlegt, wie es das am besten formuliert …" : "thinking about how to put this …";
    }

    /** Transient activity while the lazy browser starts for a research run. */
    public String browserStarting() {
        return de() ? "Starte Browser …" : "Starting browser…";
    }

    /** Finishes the "Starte Browser" bubble once the browser is ready. */
    public String browserReady() {
        return de() ? "Browser bereit." : "Browser ready.";
    }

    /** A visible, honest failure when the browser could not be started (no success, no state progress). */
    public String browserFailed(String detail) {
        String suffix = detail == null || detail.trim().isEmpty() ? "" : " (" + detail.trim() + ")";
        return de()
                ? "**Der Browser konnte nicht gestartet werden.**" + suffix
                        + " Ich empfehle, die Recherche erneut zu starten; falls das Problem bleibt, prüfe "
                        + "die Browser-Einstellungen (Browser-Kanal)."
                : "**The browser could not be started.**" + suffix
                        + " I recommend starting the research again; if the problem persists, check the "
                        + "browser settings (browser channel).";
    }

    /** The card's live counters + a readable current activity (never enum names or raw URLs). */
    public String progressLine(int pages, int sources, int hosts, String activityToken) {
        String counters = de()
                ? pages + " Seiten geprüft · " + sources + " Quellen aufgenommen · " + hosts
                        + (hosts == 1 ? " Website" : " Websites")
                : pages + " pages checked · " + sources + " sources recorded · " + hosts
                        + (hosts == 1 ? " website" : " websites");
        return counters + "\n" + activityLabel(activityToken);
    }

    private String activityLabel(String token) {
        String t = token == null ? "" : token;
        if ("SEARCHING".equals(t)) {
            return de() ? "Suche läuft …" : "Searching …";
        }
        if ("SEARCHING_API".equals(t)) {
            return de() ? "Suche läuft (per API, ohne Browser) …" : "Searching (via API, no browser) …";
        }
        if ("READING_PAGE".equals(t) || "OPENING_PAGE".equals(t)) {
            return de() ? "Seite wird gelesen …" : "Reading the page …";
        }
        if ("SOURCE_ACCEPTED".equals(t) || "RECORDING_SOURCE".equals(t)) {
            return de() ? "Als Quelle aufgenommen" : "Recorded as a source";
        }
        if ("PAGE_SKIPPED".equals(t)) {
            return de() ? "Geprüft – nicht relevant" : "Checked – not relevant";
        }
        if ("WAITING_FOR_USER".equals(t)) {
            return de() ? "Warte auf deine Eingabe im Browser …"
                    : "Waiting for your input in the browser …";
        }
        return de() ? "Arbeite …" : "Working …";
    }

    /** Persistent notice when a website demands a manual security check (CAPTCHA) from the user. */
    public String attentionRequired(String domain) {
        String site = domain == null || domain.isEmpty() ? (de() ? "Eine Website" : "A website") : domain;
        return de()
                ? "**Manuelle Eingabe erforderlich**\n\n" + site + " verlangt eine Sicherheitsprüfung. "
                        + "Bitte löse die Aufgabe (z. B. „Noch ein letzter Schritt“) im geöffneten "
                        + "Browserfenster. Die Recherche wird auf anderen Websites fortgesetzt."
                : "**Manual input required**\n\n" + site + " requires a security check. "
                        + "Please solve the challenge (e.g. \"One last step\") in the open browser "
                        + "window. The research continues on other websites.";
    }

    /** Visible all-clear once the user solved the manual security check. */
    public String attentionResolved(String domain) {
        String site = domain == null || domain.isEmpty() ? (de() ? "Die Website" : "The website") : domain;
        return de()
                ? "Sicherheitsprüfung gelöst — " + site + " steht der Recherche wieder zur Verfügung."
                : "Security check solved — " + site + " is available to the research again.";
    }

    /** Summary shown when the progress card completes. */
    public String runFinishedSummary(int pages, int sources, int hosts) {
        return de()
                ? "Recherche-Durchlauf beendet — " + pages + " Seiten, " + sources + " Quellen, "
                        + hosts + (hosts == 1 ? " Website" : " Websites")
                : "Research pass finished — " + pages + " pages, " + sources + " sources, "
                        + hosts + (hosts == 1 ? " website" : " websites");
    }

    // ------------------------------------------------------------------ run outcome card (result + decision)

    /**
     * The user-facing result card for a terminal run outcome: what was achieved, why the run ended, what is
     * still missing and what the agent recommends — plain language, no stop-reason enum names, no internal
     * ids, no raw URLs. The matching actions are chosen by the session.
     */
    public String outcomeCard(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        String stop = o.getStopReason();
        boolean sufficient = o.isEvidenceSufficient();
        StringBuilder sb = new StringBuilder();
        if ("SUFFICIENT_EVIDENCE".equals(stop) || ("SOURCE_BUDGET_EXHAUSTED".equals(stop) && sufficient)) {
            sb.append(de()
                    ? "**Die Evidenzsammlung ist abgeschlossen.**\n\nIch habe " + achieved(o)
                            + " Die Mindestanforderungen an Quellen und Quellenvielfalt sind erfüllt. "
                            + "Ich empfehle, die Belege jetzt zu prüfen."
                    : "**The evidence collection is complete.**\n\nI recorded " + achieved(o)
                            + " The minimum requirements for sources and source diversity are met. "
                            + "I recommend reviewing the evidence now.");
            return sb.toString();
        }
        if ("USER_CANCELLED".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde pausiert.**\n\nBisher habe ich " + achieved(o)
                            + " Du kannst jederzeit fortsetzen oder die Recherche beenden."
                    : "**The research was paused.**\n\nSo far I recorded " + achieved(o)
                            + " You can continue at any time or end the research.";
        }
        if ("MCP_UNAVAILABLE".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde durch ein technisches Problem unterbrochen.**\n\n"
                            + "Die Browser- oder Recherche-Werkzeuge waren nicht erreichbar. Bisher habe ich "
                            + achieved(o) + " Ich empfehle, es erneut zu versuchen; falls das Problem "
                            + "bleibt, hilft ein Blick in die Runtime-Konfiguration."
                    : "**The research was interrupted by a technical problem.**\n\n"
                            + "The browser or research tools were unreachable. So far I recorded "
                            + achieved(o) + " I recommend trying again; if the problem persists, "
                            + "check the runtime configuration.";
        }
        if ("ERROR_BUDGET_EXHAUSTED".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde nach mehreren Fehlversuchen angehalten.**\n\nBisher habe ich "
                            + achieved(o) + " Ich empfehle, es erneut zu versuchen."
                    : "**The research stopped after several consecutive errors.**\n\nSo far I recorded "
                            + achieved(o) + " I recommend trying again.";
        }
        // Typed reranker stops (A5): technical failures are named as such — NEVER presented as a
        // generic budget stop, and the semantic "nothing relevant" is not a technical failure either.
        if ("RERANKER_UNAVAILABLE".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde durch ein technisches Problem unterbrochen.**\n\n"
                            + "Die lokale Relevanzbewertung (Reranker) war nicht erreichbar, deshalb "
                            + "wurden keine Suchtreffer geöffnet. Bisher habe ich " + achieved(o)
                            + " Ich empfehle, es erneut zu versuchen; falls das Problem bleibt, hilft "
                            + "ein Blick in die Research-Runtime-Einstellungen."
                    : "**The research was interrupted by a technical problem.**\n\n"
                            + "The local relevance ranking (reranker) was not reachable, so no search "
                            + "results were opened. So far I recorded " + achieved(o)
                            + " I recommend trying again; if the problem persists, check the "
                            + "research runtime settings.";
        }
        if ("RERANKER_TIMEOUT".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde durch ein technisches Problem unterbrochen.**\n\n"
                            + "Die lokale Relevanzbewertung (Reranker) hat nicht rechtzeitig "
                            + "geantwortet. Bisher habe ich " + achieved(o)
                            + " Ich empfehle, es erneut zu versuchen und bei wiederholtem Auftreten "
                            + "das Zeitlimit des Rerankers in der Konfiguration zu prüfen."
                    : "**The research was interrupted by a technical problem.**\n\n"
                            + "The local relevance ranking (reranker) did not answer in time. "
                            + "So far I recorded " + achieved(o)
                            + " I recommend trying again and, if this repeats, checking the "
                            + "reranker timeout in the configuration.";
        }
        if ("RERANKER_INVALID_RESPONSE".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde durch ein technisches Problem unterbrochen.**\n\n"
                            + "Die lokale Relevanzbewertung (Reranker) hat eine inkompatible oder "
                            + "beschädigte Antwort geliefert. Bisher habe ich " + achieved(o)
                            + " Ich empfehle, es erneut zu versuchen und die lokale Modell-Runtime "
                            + "bzw. das ausgewählte Reranker-Modell zu prüfen."
                    : "**The research was interrupted by a technical problem.**\n\n"
                            + "The local relevance ranking (reranker) returned an incompatible or "
                            + "corrupted answer. So far I recorded " + achieved(o)
                            + " I recommend trying again and checking the local model runtime and "
                            + "the selected reranker model.";
        }
        if ("RERANKER_CONFIGURATION_ERROR".equals(stop)) {
            return de()
                    ? "**Die Recherche konnte den konfigurierten Reranker nicht verwenden.**\n\n"
                            + "Die Reranker-Konfiguration oder Modellauswahl ist ungültig oder nicht "
                            + "mehr nutzbar. Bisher habe ich " + achieved(o)
                            + " Bitte öffne die Research-Runtime-Einstellungen und wähle ein "
                            + "installiertes Reranker-Modell aus."
                    : "**The research could not use the configured reranker.**\n\n"
                            + "The reranker configuration or model selection is invalid or no longer "
                            + "usable. So far I recorded " + achieved(o)
                            + " Please open the research runtime settings and select an installed "
                            + "reranker model.";
        }
        if ("SEARCH_TECHNICAL_PROBLEM".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde durch ein technisches Problem unterbrochen.**\n\n"
                            + "Die Suchergebnisse konnten nicht ausgewertet werden – die Suchmaschinen-Seite "
                            + "war nicht auslesbar (z. B. war die Layout-Analyse auf ein lokales Modell "
                            + "angewiesen, das nicht erreichbar war, oder alle Suchmaschinen waren durch ein "
                            + "Captcha blockiert). Das ist kein „keine Treffer\": es waren möglicherweise "
                            + "Treffer vorhanden, die nicht ausgewertet werden konnten. Bisher habe ich "
                            + achieved(o) + " Ich empfehle, es erneut zu versuchen; falls das Problem bleibt, "
                            + "prüfe, ob die lokale Modell-Runtime (bzw. Ollama) läuft."
                    : "**The research was interrupted by a technical problem.**\n\n"
                            + "The search results could not be evaluated — the search engine page was not "
                            + "readable (for example the layout analysis relied on a local model that was "
                            + "unreachable, or every engine was blocked by a captcha). This is not \"no "
                            + "results\": there may have been hits that could not be evaluated. So far I "
                            + "recorded " + achieved(o) + " I recommend trying again; if the problem persists, "
                            + "check that the local model runtime (or Ollama) is running.";
        }
        if ("NO_SEMANTIC_MATCHES".equals(stop)) {
            return de()
                    ? "**Kein Suchtreffer war deiner Frage ähnlich genug, um geöffnet zu werden.**\n\n"
                            + "Die Relevanzbewertung hat alle Kandidaten geprüft; keiner hat die "
                            + "Auswahlkriterien bestanden. Bisher habe ich " + achieved(o) + " "
                            + missing(o) + " Ich empfehle, den Suchauftrag zu präzisieren oder "
                            + "andere Suchbegriffe zu wählen."
                    : "**No search result was similar enough to your question to be opened.**\n\n"
                            + "The relevance ranking checked every candidate; none passed the "
                            + "selection criteria. So far I recorded " + achieved(o) + " "
                            + missing(o) + " I recommend refining the research scope or trying "
                            + "different search terms.";
        }
        if ("NO_RELEVANT_PATHS".equals(stop) && !sufficient) {
            return de()
                    ? "**Ich habe keine weiteren passenden Seiten zu deiner Frage gefunden.**\n\n"
                            + "Bisher habe ich " + achieved(o) + " " + missing(o)
                            + " Ich empfehle, den Suchauftrag zu präzisieren oder andere Suchbegriffe "
                            + "zu wählen."
                    : "**I found no further pages matching your question.**\n\n"
                            + "So far I recorded " + achieved(o) + " " + missing(o)
                            + " I recommend refining the research scope or trying different search terms.";
        }
        // Budget exhausted (tool/page/time) or other recoverable stops with open requirements.
        sb.append(de()
                ? "**Die Recherche ist noch nicht belastbar abgeschlossen.**\n\nIch habe " + achieved(o)
                        + " " + missing(o) + " Mein Budget für diesen Durchlauf ist aufgebraucht. "
                        + recommendation(o)
                : "**The research is not reliably complete yet.**\n\nI recorded " + achieved(o)
                        + " " + missing(o) + " My budget for this pass is used up. "
                        + recommendation(o));
        return sb.toString();
    }

    private String achieved(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        int s = o.getAcceptedSources();
        int h = o.getDistinctHosts();
        return de()
                ? s + (s == 1 ? " relevante Quelle" : " relevante Quellen") + " von " + h
                        + (h == 1 ? " Website" : " verschiedenen Websites") + " aufgenommen."
                : s + (s == 1 ? " relevant source" : " relevant sources") + " from " + h
                        + (h == 1 ? " website" : " different websites") + ".";
    }

    private String missing(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        if ("INSUFFICIENT_SOURCES".equals(o.getLimitation())) {
            return de()
                    ? "Für ein belastbares Ergebnis fehlen noch Quellen (mindestens "
                            + o.getMinimumSources() + " nötig)."
                    : "More sources are needed for a reliable result (at least "
                            + o.getMinimumSources() + " required).";
        }
        if ("INSUFFICIENT_HOST_DIVERSITY".equals(o.getLimitation())) {
            return de()
                    ? "Die Quellenvielfalt reicht noch nicht: Es fehlen unabhängige Websites "
                            + "(mindestens " + o.getMinimumDistinctHosts() + " verschiedene nötig)."
                    : "Source diversity is not sufficient yet: independent websites are missing "
                            + "(at least " + o.getMinimumDistinctHosts() + " different ones required).";
        }
        return de() ? "Die Mindestanforderungen sind erfüllt." : "The minimum requirements are met.";
    }

    private String recommendation(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        String action = o.getRecommendedAction();
        if ("CONTINUE_RESEARCH".equals(action)) {
            return de()
                    ? "Ich empfehle, gezielt nach weiteren unabhängigen Websites zu suchen."
                    : "I recommend searching specifically for additional independent websites.";
        }
        if ("REFINE_RESEARCH_SCOPE".equals(action)) {
            return de()
                    ? "Ich empfehle, den Suchauftrag zu präzisieren."
                    : "I recommend refining the research scope.";
        }
        if ("REVIEW_EVIDENCE".equals(action)) {
            return de()
                    ? "Ich empfehle, die vorhandenen Belege zu prüfen."
                    : "I recommend reviewing the collected evidence.";
        }
        if ("RETRY".equals(action)) {
            return de()
                    ? "Ich empfehle, es erneut zu versuchen."
                    : "I recommend trying again.";
        }
        if ("OPEN_CONFIGURATION".equals(action)) {
            return de()
                    ? "Ich empfehle, die Research-Runtime-Einstellungen zu öffnen."
                    : "I recommend opening the research runtime settings.";
        }
        return de() ? "Du entscheidest, wie es weitergeht." : "You decide how to proceed.";
    }

    /** Localized labels for the typed result-card actions (ids are stable, labels are language-bound). */
    public String actionLabel(String actionId) {
        if ("continue".equals(actionId)) {
            return de() ? "Weiterrecherchieren" : "Continue research";
        }
        if ("sources".equals(actionId)) {
            return de() ? "Quellen ansehen" : "View sources";
        }
        if ("refine".equals(actionId)) {
            return de() ? "Suchauftrag ergänzen" : "Refine scope";
        }
        if ("limit".equals(actionId)) {
            return de() ? "Mit Einschränkung fortfahren" : "Continue with limitation";
        }
        if ("end".equals(actionId)) {
            return de() ? "Recherche beenden" : "End research";
        }
        if ("review".equals(actionId)) {
            return de() ? "Belege prüfen" : "Review evidence";
        }
        if ("retry".equals(actionId)) {
            return de() ? "Erneut versuchen" : "Try again";
        }
        if ("config".equals(actionId)) {
            return de() ? "Konfiguration öffnen" : "Open configuration";
        }
        if ("resume".equals(actionId)) {
            return de() ? "Fortsetzen" : "Resume";
        }
        if ("approve".equals(actionId)) {
            return de() ? "Freigeben" : "Approve";
        }
        if ("changes".equals(actionId)) {
            return de() ? "Änderungen anfordern" : "Request changes";
        }
        return actionId;
    }

    /** Visible confirmation once the user chose to proceed despite an unmet evidence requirement. */
    public String limitationRecorded(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        return de()
                ? "Einschränkung festgehalten: " + missing(o) + " Der aktuelle Stand wird NICHT als "
                        + "uneingeschränkt ausreichend behandelt."
                : "Limitation recorded: " + missing(o) + " The current state is NOT treated as "
                        + "unconditionally sufficient.";
    }

    /** The focused follow-up when the user chose to refine the research scope. */
    public String refinePrompt() {
        return de()
                ? "Gern — welche Richtung soll ich ergänzen oder ändern? Nenne z. B. zusätzliche "
                        + "Aspekte, andere Suchbegriffe oder Quellenarten."
                : "Sure — which direction should I add or change? For example additional aspects, "
                        + "different search terms or types of sources.";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
