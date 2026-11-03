package com.aresstack.askai.research.host;

import org.junit.Test;

import javax.swing.JComboBox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RegionChoicesTest {

    @Test
    public void dataForSeoLocationShowsCountryNameForStoredCode() {
        JComboBox<RegionChoices.Choice> location = RegionChoices.dataForSeoLocations();
        RegionChoices.select(location, "2276");
        assertEquals("Germany", location.getSelectedItem().toString());
        RegionChoices.select(location, "United States");
        assertEquals("2840", RegionChoices.selected(location));
    }

    @Test
    public void languageShowsEnglishNameAndStoresCode() {
        JComboBox<RegionChoices.Choice> language = RegionChoices.languages();
        RegionChoices.select(language, "de");
        assertEquals("German", language.getSelectedItem().toString());
        assertEquals("de", RegionChoices.selected(language));
    }

    @Test
    public void countryCaseFollowsProvider() {
        JComboBox<RegionChoices.Choice> upper = RegionChoices.countries(true);
        RegionChoices.select(upper, "de");
        assertEquals("DE", RegionChoices.selected(upper));
        assertEquals("Germany", upper.getSelectedItem().toString());
    }

    @Test
    public void emptyMeansProviderDefaultAndUnknownCodeIsKept() {
        JComboBox<RegionChoices.Choice> location = RegionChoices.dataForSeoLocations();
        RegionChoices.select(location, null);
        assertNull(RegionChoices.selected(location));
        RegionChoices.select(location, "1003854");
        assertEquals("1003854", RegionChoices.selected(location));
    }
}
