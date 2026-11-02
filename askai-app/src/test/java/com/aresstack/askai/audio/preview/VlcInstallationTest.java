package com.aresstack.askai.audio.preview;

import com.aresstack.audio.openal.OpenAlDevice;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The manual VLC executable setting: choosing vlc.exe or a VLCPortable.exe persists through the existing
 * {@link VlcInstallation} preferences, invalid choices are rejected, Clear falls back to auto-detection,
 * and a configured (available) VLC surfaces as an output device.
 */
public class VlcInstallationTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void acceptsAPlainVlcExecutable() throws Exception {
        File vlc = folder.newFile("vlc.exe");
        assertEquals(vlc, VlcInstallation.resolveChosenExecutable(vlc));
    }

    @Test
    public void resolvesVlcPortableToItsBundledEngine() throws Exception {
        File dir = folder.newFolder("VLCPortable");
        File portable = new File(dir, "VLCPortable.exe");
        assertTrue(portable.createNewFile());
        File engineDir = new File(new File(dir, "App"), "vlc");
        assertTrue(engineDir.mkdirs());
        File engine = new File(engineDir, "vlc.exe");
        assertTrue(engine.createNewFile());

        assertEquals("VLCPortable.exe resolves to App/vlc/vlc.exe",
                engine, VlcInstallation.resolveChosenExecutable(portable));
    }

    @Test
    public void rejectsVlcPortableWithoutABundledEngine() throws Exception {
        File dir = folder.newFolder("VLCPortableBroken");
        File portable = new File(dir, "VLCPortable.exe");
        assertTrue(portable.createNewFile());
        assertNull(VlcInstallation.resolveChosenExecutable(portable));
    }

    @Test
    public void rejectsUnrelatedOrMissingFiles() throws Exception {
        assertNull("a non-VLC executable is rejected",
                VlcInstallation.resolveChosenExecutable(folder.newFile("notepad.exe")));
        assertNull("a directory is rejected", VlcInstallation.resolveChosenExecutable(folder.newFolder("dir")));
        assertNull("a missing file is rejected",
                VlcInstallation.resolveChosenExecutable(new File(folder.getRoot(), "nope.exe")));
        assertNull("null is rejected", VlcInstallation.resolveChosenExecutable(null));
    }

    @Test
    public void persistsAndClearsTheChosenExecutable() throws Exception {
        Preferences node = Preferences.userRoot().node("com/aresstack/askai/test/vlc/" + System.nanoTime());
        try {
            VlcInstallation vlc = new VlcInstallation(node);
            File exe = folder.newFile("vlc.exe");

            vlc.setExecutable(exe);
            assertEquals(exe.getAbsolutePath(), vlc.getConfiguredPath());
            assertEquals(exe, vlc.resolve());
            assertTrue(vlc.isAvailable());

            vlc.clearExecutable();
            assertEquals("", vlc.getConfiguredPath());
        } finally {
            node.removeNode();
        }
    }

    @Test
    public void anAvailableVlcAppearsInTheOutputDeviceList() {
        List<AudioOutputDevice> withVlc = new AudioOutputDeviceCatalog(
                new AudioOutputDeviceCatalog.OpenAlDeviceSource() {
                    public List<OpenAlDevice> list() {
                        return Collections.<OpenAlDevice>emptyList();
                    }
                },
                new AudioOutputDeviceCatalog.VlcAvailability() {
                    public boolean isAvailable() {
                        return true;
                    }
                }).findAll();
        assertTrue("VLC output device present when a VLC install is available", containsVlc(withVlc));

        List<AudioOutputDevice> withoutVlc = new AudioOutputDeviceCatalog(
                new AudioOutputDeviceCatalog.OpenAlDeviceSource() {
                    public List<OpenAlDevice> list() {
                        return Collections.<OpenAlDevice>emptyList();
                    }
                },
                new AudioOutputDeviceCatalog.VlcAvailability() {
                    public boolean isAvailable() {
                        return false;
                    }
                }).findAll();
        assertFalse("no VLC device when none is available", containsVlc(withoutVlc));
    }

    private static boolean containsVlc(List<AudioOutputDevice> devices) {
        for (AudioOutputDevice device : devices) {
            if (device.isVlc()) {
                return true;
            }
        }
        return false;
    }
}
