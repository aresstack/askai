package com.aresstack.askai.tts;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The curated voice catalog is well-formed; the store's installed check is fail-closed. */
public class PiperVoiceCatalogTest {

    @Test
    public void curatedVoicesAreUniqueAndWellFormed() {
        Set<String> ids = new HashSet<String>();
        for (PiperVoice voice : PiperVoiceCatalog.curated()) {
            assertTrue("unique id: " + voice.getId(), ids.add(voice.getId()));
            assertTrue(voice.getApproximateSizeMb() > 0);
            assertTrue("hf path has language depth", voice.getHfPath().split("/").length >= 3);
            assertEquals(voice.getId() + ".onnx", voice.onnxFileName());
            assertEquals(voice.getId() + ".onnx.json", voice.configFileName());
            assertTrue("the language code opens the voice id (piper convention: de_DE-…)",
                    voice.getId().startsWith(voice.getLanguageCode() + "_"));
            assertTrue("…and opens the repo path", voice.getHfPath()
                    .startsWith(voice.getLanguageCode() + "/"));
            // The voice id embeds the quality step that ends the hf path (piper convention).
            String quality = voice.getHfPath().substring(voice.getHfPath().lastIndexOf('/') + 1);
            assertTrue(voice.getId() + " ends with " + quality, voice.getId().endsWith(quality));
        }
        assertTrue("German and English are both offered", ids.contains("de_DE-thorsten-high")
                && ids.contains("en_US-lessac-high"));
    }

    @Test
    public void findByIdToleratesUnknownAndNull() {
        assertNull(PiperVoiceCatalog.findById(null));
        assertNull(PiperVoiceCatalog.findById("no-such-voice"));
        assertEquals("de_DE-thorsten-high",
                PiperVoiceCatalog.findById(" de_DE-thorsten-high ").getId());
    }

    @Test
    public void aVoiceCountsAsInstalledOnlyWithBothFiles() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("tts-store");
        try {
            PiperTtsStore store = new PiperTtsStore(root);
            PiperVoice voice = PiperVoiceCatalog.curated().get(0);
            assertTrue(store.installedVoices().isEmpty());
            java.nio.file.Files.createDirectories(store.voiceDirectory(voice));
            java.nio.file.Files.write(store.voiceModelFile(voice), new byte[]{1});
            assertTrue("model alone is NOT installed (half-written download)",
                    !store.isVoiceInstalled(voice));
            java.nio.file.Files.write(store.voiceConfigFile(voice), new byte[]{1});
            assertTrue(store.isVoiceInstalled(voice));
            assertEquals(1, store.installedVoices().size());
            assertTrue("engine missing → not ready to speak", !store.isReadyToSpeak(voice));
        } finally {
            deleteRecursively(root.toFile());
        }
    }

    private static void deleteRecursively(java.io.File file) {
        java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
