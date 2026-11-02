package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpModelCatalog;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;

import java.util.ArrayList;
import java.util.List;

/**
 * The productive {@link NlpModelCatalog}: lists the installed models of the {@link LocalNlpModelStore} that match
 * a (capability, language), for the EXPLICIT selection in the settings UI. It never installs or downloads.
 */
public final class LocalNlpModelCatalog implements NlpModelCatalog {

    private final LocalNlpModelStore store;

    public LocalNlpModelCatalog(LocalNlpModelStore store) {
        this.store = store;
    }

    @Override
    public List<String> listInstalledModels(NlpCapability capability, String languageCode) {
        String language = languageCode == null ? "" : languageCode.trim().toLowerCase();
        List<String> ids = new ArrayList<String>();
        for (NlpModelDescriptor descriptor : store.listInstalled()) {
            if (descriptor.getCapability() == capability && descriptor.getLanguageCode().equals(language)) {
                ids.add(descriptor.getModelId());
            }
        }
        return ids; // store.listInstalled() is already sorted by id
    }
}
