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
    public void aSecondSessionGetsATemporaryProfileThatIsDeletedOnClose() throws Exception {
        Path profiles = temporary.getRoot().toPath();
        CamoufoxProvisioning.ProfileLease first = CamoufoxProvisioning.leaseProfile(profiles);
        CamoufoxProvisioning.ProfileLease second = CamoufoxProvisioning.leaseProfile(profiles);
        try {
            assertEquals(profiles.resolve("camoufox"), first.directory());
            assertNotEquals(first.directory(), second.directory());
            assertTrue(Files.isDirectory(second.directory()));
            Files.write(second.directory().resolve("prefs.js"), new byte[]{1});
        } finally {
            second.close();
        }
        assertFalse("temporary profile removed", Files.exists(second.directory()));
        first.close();
        assertTrue("the persistent profile stays", Files.isDirectory(first.directory()));

        CamoufoxProvisioning.ProfileLease again = CamoufoxProvisioning.leaseProfile(profiles);
        assertEquals("released: the next session owns the persistent profile again",
                profiles.resolve("camoufox"), again.directory());
        again.close();
    }
}
