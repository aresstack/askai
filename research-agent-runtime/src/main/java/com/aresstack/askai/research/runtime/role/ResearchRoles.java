package com.aresstack.askai.research.runtime.role;

import com.aresstack.askai.research.runtime.acquire.ModelPageReadinessJudge;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.scope.MainModelScopeAdviceChooser;
import com.aresstack.askai.research.runtime.scope.MainModelScopeProbeGenerator;
import com.aresstack.askai.research.runtime.team.PhaseAssistantProfile;
import com.aresstack.askai.research.runtime.team.PhaseAssistantProfileRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The research agent's model roles (ADR 0001, 1.3 and 4.1). The conversation role is one role per phase
 * profile ({@link PhaseAssistantProfileRegistry}); the worker roles are declared next to the code that runs
 * them, so each prompt has exactly one home and stays byte for byte what it was before the role interface.
 * The narrator is not listed: it streams over the host's {@code AgentInferencePort}, not over a role chat.
 */
public final class ResearchRoles {

    /**
     * The conversation role the TeamAgent's chat transport is bound to: the conversation slot with the slot's
     * default context. Every phase profile runs on this one transport; a profile's role declares the prompt,
     * tools and output of its phase.
     */
    public static final ResearchRole CONVERSATION = PhaseAssistantProfileRegistry.defaults().fallback().getRole();

    /** The scope sweep's probe generator. */
    public static final ResearchRole SCOPE_PROBE_GENERATOR = MainModelScopeProbeGenerator.ROLE;

    /** The scope sweep's advice chooser. */
    public static final ResearchRole SCOPE_ADVICE_CHOOSER = MainModelScopeAdviceChooser.ROLE;

    /** The acquisition runner's page readiness judge. */
    public static final ResearchRole PAGE_READINESS_JUDGE = ModelPageReadinessJudge.ROLE;

    /**
     * The model-backed SERP layout repair. Its prompts are built per repair ticket by
     * {@code browser-search-analysis} (another module's contract), hence {@code CALLER}; the answer is a JSON
     * decision validated by that module's codec.
     */
    public static final ResearchRole SERP_LAYOUT_REPAIR = ResearchRole.builder("serp-layout-repair",
                    ResearchModelSlots.LAYOUT_REPAIR)
            .purpose("repairs the result layout of a search page the extractor could not read")
            .promptFromCaller()
            .output(RoleOutput.json("SearchLayoutRepairJson"))
            .build();

    private ResearchRoles() {
    }

    /** The worker roles in a fixed order (everything but the conversation). */
    public static List<ResearchRole> workers() {
        List<ResearchRole> roles = new ArrayList<ResearchRole>();
        roles.add(SCOPE_PROBE_GENERATOR);
        roles.add(SCOPE_ADVICE_CHOOSER);
        roles.add(PAGE_READINESS_JUDGE);
        roles.add(SERP_LAYOUT_REPAIR);
        return Collections.unmodifiableList(roles);
    }

    /** Every role of a session: one conversation role per phase profile of {@code profiles}, then the workers. */
    public static List<ResearchRole> all(PhaseAssistantProfileRegistry profiles) {
        List<ResearchRole> roles = new ArrayList<ResearchRole>();
        for (PhaseAssistantProfile profile : profiles.all()) {
            roles.add(profile.getRole());
        }
        roles.addAll(workers());
        return Collections.unmodifiableList(roles);
    }
}
