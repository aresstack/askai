package com.aresstack.askai.browser.playwright;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Camoufox as the default channel of the sidecar: channel choice, readiness and the profile lease. */
public class CamoufoxChannelTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void camoufoxIsTheDefaultChannel() {
        assertEquals("camoufox", PlaywrightSessionFactory.normalizeChannel(null));
        assertEquals("camoufox", PlaywrightSessionFactory.normalizeChannel("camoufox"));
        assertEquals("chrome", PlaywrightSessionFactory.normalizeChannel("chrome"));
        assertEquals("msedge", PlaywrightSessionFactory.normalizeChannel("MSEDGE"));
    }

    @Test
    public void theManagedCamoufoxIsReadyWithoutALocallyInstalledBrowser() {
        PlaywrightCapabilityProbe probe = new PlaywrightCapabilityProbe(new PlaywrightCapabilityProbe.Checks() {
            public List<String> driverCommand() {
                return Arrays.asList("java", "com.aresstack.playwright4j.driver.GraalDriverMain");
            }

            public boolean driverBundlePresent() {
                return true;
            }

            public String browserExecutable(String channel) {
                return null; // no Chrome/Edge on this machine
            }
        });
        assertEquals(PlaywrightReadiness.Status.READY, probe.probe("camoufox").getStatus());
        assertEquals(PlaywrightReadiness.Status.BROWSER_NOT_INSTALLED, probe.probe("chrome").getStatus());
    }

    @Test
    public void eachChatOwnsItsPersistentProfile() throws Exception {
        Path profiles = temporary.getRoot().toPath();
        CamoufoxProvisioning.ProfileLease chatA = CamoufoxProvisioning.leaseProfile(profiles, "chat-a");
        CamoufoxProvisioning.ProfileLease chatB = CamoufoxProvisioning.leaseProfile(profiles, "chat-b");
        try {
            assertFalse("persistent", chatA.isTemporary());
            assertFalse("persistent", chatB.isTemporary());
            assertNotEquals("chats never share a profile", chatA.directory(), chatB.directory());
            assertEquals(CamoufoxProvisioning.chatProfileDirectory(profiles, "chat-a").resolve("camoufox"),
                    chatA.directory());
            assertFalse("no chat id in the path", chatA.directory().toString().contains("chat-a"));
            Files.write(chatA.directory().resolve("cookies.sqlite"), new byte[]{1});
        } finally {
            chatA.close();
            chatB.close();
        }
        CamoufoxProvisioning.ProfileLease reopened = CamoufoxProvisioning.leaseProfile(profiles, "chat-a");
        try {
            assertEquals("the chat finds its profile again", chatA.directory(), reopened.directory());
            assertTrue("its sign-ins survived", Files.exists(reopened.directory().resolve("cookies.sqlite")));
        } finally {
            reopened.close();
        }
        CamoufoxProvisioning.ProfileLease other = CamoufoxProvisioning.leaseProfile(profiles, "chat-b");
        try {
            assertFalse("chat B never sees chat A's cookies",
                    Files.exists(other.directory().resolve("cookies.sqlite")));
        } finally {
            other.close();
        }
    }

    @Test
    public void aSecondBrowserOfTheSameChatGetsATemporaryProfileThatIsDeletedOnClose() throws Exception {
        Path profiles = temporary.getRoot().toPath();
        CamoufoxProvisioning.ProfileLease first = CamoufoxProvisioning.leaseProfile(profiles, "chat-a");
        CamoufoxProvisioning.ProfileLease second = CamoufoxProvisioning.leaseProfile(profiles, "chat-a");
        try {
            assertTrue(second.isTemporary());
            assertNotEquals(first.directory(), second.directory());
            assertEquals("stays inside the chat's own directory",
                    CamoufoxProvisioning.chatProfileDirectory(profiles, "chat-a"), second.directory().getParent());
            Files.write(second.directory().resolve("prefs.js"), new byte[]{1});
        } finally {
            second.close();
        }
        assertFalse("temporary profile removed", Files.exists(second.directory()));
        first.close();
        assertTrue("the persistent profile stays", Files.isDirectory(first.directory()));

        CamoufoxProvisioning.ProfileLease again = CamoufoxProvisioning.leaseProfile(profiles, "chat-a");
        assertEquals("released: the chat's next browser owns its persistent profile again",
                first.directory(), again.directory());
        again.close();
    }

    @Test
    public void withoutAChatThereIsNoSharedPersistentProfile() throws Exception {
        Path profiles = temporary.getRoot().toPath();
        CamoufoxProvisioning.ProfileLease first = CamoufoxProvisioning.leaseProfile(profiles, null);
        CamoufoxProvisioning.ProfileLease second = CamoufoxProvisioning.leaseProfile(profiles, " ");
        assertTrue(first.isTemporary());
        assertTrue(second.isTemporary());
        assertNotEquals(first.directory(), second.directory());
        first.close();
        second.close();
        assertFalse(Files.exists(first.directory()));
        assertFalse("no app-wide profile is created", Files.exists(profiles.resolve("camoufox")));
    }
}
