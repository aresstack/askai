package com.aresstack.askai.ui.setup;

/**
 * Witch, the setup wizard's companion: his personality as fixed lines per step. Everything here is deterministic so
 * that setup, failures and hints are always right; the chat model only joins for a free question
 * ({@link #systemPrompt(int)}). Until an AI runtime answers, Witch stays silent; then he introduces himself (in
 * detail on the first start, as a reunion when called again from the settings) and says a short line in each step.
 * Knows neither Swing nor speech output; that is {@link WizardNarrator}'s job.
 *
 * <p>Ported from the Enterprise AI Client; the lines follow AskAI's steps (network, runtime, chat model, knowledge,
 * dictation, check). Index = step index of {@link SetupWizardPanel}.</p>
 */
final class WizardGuide {

    static final String NAME = "Witch";

    static final String FIRST_GREETING = "Ah, wonderful – now I can finally talk to you! I'm the Witch, your personal "
            + "assistant. I'll walk you through the rest of the setup – and afterwards you won't get rid of me that "
            + "easily.";
    static final String WELCOME_BACK = "There you are again. What shall we change about my setup this time?";
    static final String RUNTIME_OK = "Excellent. The AI runtime answers. Now I'll pick a few suitable models for us.";
    static final String SENTENCE_MODELS_OK = "Splendid, the sentence models are installed. Now I can read texts "
            + "sentence by sentence.";
    static final String FAILURE = "That didn't work just now. Your entries are still there, though – we can simply "
            + "try again.";
    /** His thought while he has neither voice nor runtime yet; only shown, never spoken. */
    static final String THOUGHT_SILENT = "(When will I finally be heard …)";
    static final String THINKING = "One moment, let me think …";
    static final String NO_ANSWER = "No answer comes to mind right now. Try again in a moment, or ask me what to do "
            + "in this step.";

    /**
     * The hand-over after the first successful setup: Witch passes on to his daughter (the chat assistant with her
     * voice), who considers him superfluous; he keeps the last word. Per line: who speaks, what.
     */
    private static final Line[] HANDOFF = {
            new Line(true, "That's it, we're done. From here on, my daughter takes over."),
            new Line(false, "Thanks, Mum. You're not needed any more. We have AI now."),
            new Line(true, "Of course, my child."),
            new Line(true, "Just call me when the AI doesn't know what to do – you'll find me in the settings.")
    };

    static int handoffLines() {
        return HANDOFF.length;
    }

    static Line handoff(int index) {
        return HANDOFF[index];
    }

    /** One line of the hand-over: Witch or the chat assistant. */
    static final class Line {
        final boolean witch;
        final String text;

        Line(boolean witch, String text) {
            this.witch = witch;
            this.text = text;
        }
    }

    private static final String[] ON_ENTER = {
            null,
            "Here we connect me with an AI runtime. As soon as that works, I can talk to you.",
            "Now I'm choosing a brain for myself. Pick the model we'll chat with.",
            "Here I learn how to search, read and rank knowledge. The better I can do that, the less I have to "
                    + "guess. You can skip this step and do it later in the settings.",
            "Shall I listen to you as well? Pick your microphone and let me hear you – or skip it, typing works "
                    + "just as well.",
            "Almost done. Let me check whether I can really do everything I claim."
    };

    private static final String[] WHAT_TO_DO = {
            "Choose how AskAI reaches the internet: without a proxy, via the Windows proxy settings or a proxy of "
                    + "your own; in company networks also trust the Windows certificates. Then pick my voice and "
                    + "click “Download voice”: if the progress bar moves, your network settings work. Working "
                    + "offline? Keep the Windows voice and skip this step.",
            "Enter the address of your Ollama server – on your own machine that is usually http://localhost:11434 – "
                    + "and click “Test connection”. No Ollama yet? “Download Ollama” takes you to it. AskAI's own "
                    + "local runtime counts too, as soon as it has a model.",
            "Choose the model we'll chat with. It is also the main model of all plugins. Not installed yet? "
                    + "“Download model” lets Ollama fetch it – the suggestion is a good start.",
            "Choose an embedding model for the knowledge search – I'll measure its vector length myself. A "
                    + "reranker ranks the sources and is required for web research: “Install recommended reranker” "
                    + "puts it into AskAI's own runtime. The sentence models split texts into sentences; “Install "
                    + "sentence models” fetches the missing ones.",
            "Pick the microphone (or keep the system default) and click “Test microphone”: if the bar moves, I "
                    + "can hear you. “Automatic” lets AskAI pick a model that understands audio; none installed? "
                    + "“Download audio model” fetches one. All of this is optional.",
            "Nothing else: I save everything and check it. If something is red, jump to that step above, fix it, "
                    + "and then click “Check again”. “Finish” ends the setup – even if something is still red."
    };

    private static final String[] WHY_NEEDED = {
            "Models, voices and web searches come from the internet. If the proxy or the certificates are wrong, "
                    + "every download fails – the voice download finds that out right away. And with a local voice I "
                    + "read answers aloud without your text leaving your computer.",
            "The runtime is my head: without it I can neither answer nor understand texts.",
            "The chat model phrases my answers. Everything else – research, summaries, tools – builds on it.",
            "The embedding model finds the passages that match your question, even when other words are used. The "
                    + "reranker sorts them by relevance, and the sentence models cut sources into clean sentences.",
            "With dictation you speak your questions instead of typing them. The recording only goes to your own AI "
                    + "runtime, which writes it down.",
            "So that you don't only notice in everyday use that something is missing: I check every building "
                    + "block for real once."
    };

    private static final String[] TOPIC = {"Connection and voice", "AI runtime", "Chat model",
            "Knowledge and research", "Dictation", "Final check"};

    /** The introduction: in detail on the first start, as a reunion when called later. */
    String greeting(boolean returning) {
        return returning ? WELCOME_BACK : FIRST_GREETING;
    }

    /** Witch's line on entering a step; {@code null} when he says nothing there. */
    String enteredStep(int step) {
        return at(ON_ENTER, step);
    }

    /** Short reaction to a failure in the step; never the technical message itself (that is in the error plate). */
    String stepFailed(int step) {
        return FAILURE;
    }

    /** "What do I have to do here?" */
    String whatToDo(int step) {
        return at(WHAT_TO_DO, step);
    }

    /** "Why do I need this?" */
    String whyNeeded(int step) {
        return at(WHY_NEEDED, step);
    }

    /** Number of steps Witch knows lines for. */
    static int steps() {
        return TOPIC.length;
    }

    /**
     * The frame of a free question to the chat model: Witch's role, the current step and its fixed hints, so the
     * answer fits the setup. Contains no user input and no credentials.
     */
    String systemPrompt(int step) {
        return "You are the Witch, a friendly, slightly mischievous hooded witch and the personal assistant in AskAI, a "
                + "desktop app for local AI chat and web research. The user is setting you up right now. Answer in "
                + "the user's language, briefly (at most three sentences), kindly and concretely, without Markdown. "
                + "Do not invent menu items or settings. Current step: " + at(TOPIC, step) + ". What to do here: "
                + at(WHAT_TO_DO, step) + " Why: " + at(WHY_NEEDED, step);
    }

    private static String at(String[] texts, int step) {
        return step >= 0 && step < texts.length ? texts[step] : null;
    }
}
