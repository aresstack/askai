package com.aresstack.askai.browser.playwright.camoufox;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** AskAI starts the built-in profile in the system language, as Chrome did (search engines answer in it). */
class CamoufoxLocaleTest {

    @Test
    void theBuiltInProfileSpeaksTheSystemLanguage() throws Exception {
        ObjectNode profile = CamoufoxLaunch.localized(CamoufoxLaunch.defaultProfile(), Locale.GERMANY);

        assertEquals("de-DE", profile.path("firefoxUserPrefs").path("intl.locale.requested").asText());
        assertEquals("de-DE,de;q=0.8,en-US;q=0.5,en;q=0.3",
                profile.path("firefoxUserPrefs").path("intl.accept_languages").asText());
        assertEquals("de", profile.path("config").path("locale:language").asText());
        assertEquals("DE", profile.path("config").path("locale:region").asText());
        assertEquals("de-DE,de,en-US,en", profile.path("config").path("locale:all").asText());
        assertEquals("de-DE,de;q=0.8,en-US;q=0.5,en;q=0.3",
                profile.path("config").path("headers.Accept-Language").asText());
        // the rest of the fingerprint stays as Argos ships it
        assertEquals("Win32", profile.path("config").path("navigator.platform").asText());
    }

    @Test
    void anUndeterminedLocaleKeepsTheProfile() throws Exception {
        ObjectNode profile = CamoufoxLaunch.defaultProfile();
        assertSame(profile, CamoufoxLaunch.localized(profile, Locale.ROOT));
        assertEquals("en-US", profile.path("firefoxUserPrefs").path("intl.locale.requested").asText());
    }
}
