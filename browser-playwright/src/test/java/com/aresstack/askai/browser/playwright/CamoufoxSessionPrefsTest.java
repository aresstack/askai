package com.aresstack.askai.browser.playwright;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CamoufoxSessionPrefsTest {

    @Test
    void addsNoBfcacheAndAutoAcceptRepost() {
        Map<String, Object> prefs = CamoufoxSessionPrefs.apply(new LinkedHashMap<String, Object>());
        assertEquals(0, prefs.get("browser.sessionhistory.max_total_viewers"));
        assertEquals(Boolean.TRUE, prefs.get("dom.confirm_repost.testing.always_accept"));
    }

    @Test
    void keepsValuesTheLaunchProfileAlreadySets() {
        Map<String, Object> prefs = new LinkedHashMap<String, Object>();
        prefs.put("dom.confirm_repost.testing.always_accept", Boolean.FALSE);
        CamoufoxSessionPrefs.apply(prefs);
        assertEquals(Boolean.FALSE, prefs.get("dom.confirm_repost.testing.always_accept"));
    }
}
