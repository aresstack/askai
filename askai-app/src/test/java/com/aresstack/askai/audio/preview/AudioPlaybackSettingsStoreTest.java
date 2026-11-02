package com.aresstack.askai.audio.preview;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The shared playback-output selection: persisted, resolved against the LIVE catalog. */
public class AudioPlaybackSettingsStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private AudioPlaybackSettingsStore store() {
        return new AudioPlaybackSettingsStore(temp.getRoot().toPath().resolve("audio.properties"));
    }

    @Test
    public void unsavedSelectionFallsBackToTheCatalogHead() {
        List<AudioOutputDevice> devices = Arrays.asList(
                AudioOutputDevice.vlcSystemDefault(), AudioOutputDevice.systemDefault());
        assertEquals(AudioOutputDevice.vlcSystemDefault(), store().resolve(devices));
        assertNull("empty catalog resolves to nothing",
                store().resolve(Collections.<AudioOutputDevice>emptyList()));
    }

    @Test
    public void aPersistedDeviceIsResolvedByBackendAndName() throws Exception {
        AudioPlaybackSettingsStore store = store();
        AudioOutputDevice openAl = AudioOutputDevice.forOpenAl("spec-1", "Speakers (OpenAL)");
        store.persistSelection(openAl);
        List<AudioOutputDevice> devices = Arrays.asList(
                AudioOutputDevice.vlcSystemDefault(),
                AudioOutputDevice.forOpenAl("spec-1", "Speakers (OpenAL)"),
                AudioOutputDevice.systemDefault());
        assertEquals("Speakers (OpenAL)", store.resolve(devices).getDisplayName());
        assertEquals(AudioOutputDevice.Backend.OPENAL, store.resolve(devices).getBackend());
    }

    @Test
    public void aVanishedDeviceFallsBackToTheHeadInsteadOfBreaking() throws Exception {
        AudioPlaybackSettingsStore store = store();
        store.persistSelection(AudioOutputDevice.forOpenAl("gone", "Unplugged headset"));
        List<AudioOutputDevice> devices = Collections.singletonList(
                AudioOutputDevice.systemDefault());
        assertEquals(AudioOutputDevice.systemDefault(), store.resolve(devices));
    }
}
