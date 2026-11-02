package com.aresstack.askai.localmodels;

import java.util.List;

/** Supplies the CURATED, installable NLP model entries the Model Browser presents (per implementation/version). */
public interface NlpModelCatalogProvider {

    /** All curated entries this provider offers (stable order). */
    List<NlpModelCatalogEntry> availableModels();
}
