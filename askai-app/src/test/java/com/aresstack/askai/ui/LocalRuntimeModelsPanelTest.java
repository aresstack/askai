package com.aresstack.askai.ui;

import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalRuntimeModelsPanelTest {

    @Test
    public void catalogOffersTheRecommendedRerankerForOneClickInstall() {
        boolean found = false;
        for (LocalRuntimeModelDescriptor d : LocalModelCatalog.runnable()) {
            if ("cross-encoder/ms-marco-MiniLM-L6-v2".equals(d.huggingFaceRepositoryId())) {
                found = true;
                assertEquals("Reranker", LocalRuntimeModelsPanel.purpose(d));
            }
        }
        assertTrue("the wizard's recommended reranker must be in the Local models tab", found);
    }

    @Test
    public void everyRunnableModelHasAPurposeLabel() {
        for (LocalRuntimeModelDescriptor d : LocalModelCatalog.runnable()) {
            String purpose = LocalRuntimeModelsPanel.purpose(d);
            assertFalse(purpose.isEmpty());
            if (d.hasCapability(ModelCapability.EMBEDDING) && !d.hasCapability(ModelCapability.RERANK)) {
                assertEquals("Embeddings", purpose);
            }
        }
    }
}
