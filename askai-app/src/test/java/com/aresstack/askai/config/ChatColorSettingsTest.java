package com.aresstack.askai.config;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import com.aresstack.comiccontrols.theme.ThemeColor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Chat color value object: hex round-trip, theme defaults, the migration rule and immutable with-copies. */
public class ChatColorSettingsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private AskAiTheme before;

    @Before
    public void remember() {
        before = AskAiTheme.active();
    }

    @After
    public void restore() {
        AskAiTheme.setActive(before);
    }

    @Test
    public void hexRoundTripIgnoresAlphaAndAcceptsHash() {
        assertEquals("1676D2", ChatColorSettings.toHex(new Color(0x1676D2)));
        assertEquals(new Color(0x1676D2), ChatColorSettings.parseHex("1676D2", Color.BLACK));
        assertEquals(new Color(0x1676D2), ChatColorSettings.parseHex("#1676D2", Color.BLACK));
    }

    @Test
    public void invalidHexFallsBackToTheDefault() {
        assertSame(Color.RED, ChatColorSettings.parseHex(null, Color.RED));
        assertSame(Color.RED, ChatColorSettings.parseHex("nothex", Color.RED));
        assertSame(Color.RED, ChatColorSettings.parseHex("", Color.RED));
    }

    @Test
    public void defaultsAreTheLivingBubbleTokensOfTheActiveTheme() {
        ChatColorSettings defaults = ChatColorSettings.defaults();
        assertTrue(defaults.getUserBackground() instanceof ThemeColor);
        AskAiTheme.setActive(AskAiTheme.classic());
        assertEquals(new Color(0xF4F4F4), defaults.getTranscriptBackground());
        assertEquals(new Color(0x1676D2), defaults.getUserBackground());
        assertEquals(Color.WHITE, defaults.getUserForeground());
        assertEquals(new Color(0x15827A), defaults.getAssistantBackground());
        assertEquals(Color.WHITE, defaults.getAssistantForeground());
        AskAiTheme.setActive(AskAiTheme.arcane());
        assertEquals(new Color(0x24406E), defaults.getUserBackground());
        assertEquals(new Color(0xF6EEDD), defaults.getTranscriptBackground());
    }

    @Test
    public void storedOldClassicDefaultMigratesToTheThemeDefault() {
        Color migrated = ChatColorSettings.fromStored("1676D2", "userBackground");
        assertTrue("not customized → follows the theme", ChatColorSettings.isThemeDefault(migrated));
        assertTrue(ChatColorSettings.isThemeDefault(ChatColorSettings.fromStored("F4F4F4", "transcriptBackground")));
        assertTrue(ChatColorSettings.isThemeDefault(ChatColorSettings.fromStored(null, "userForeground")));
        assertTrue(ChatColorSettings.isThemeDefault(ChatColorSettings.fromStored("garbage", "userForeground")));
    }

    @Test
    public void storedDeviatingValueIsARealUserChoiceAndStays() {
        Color kept = ChatColorSettings.fromStored("123456", "userBackground");
        assertFalse(ChatColorSettings.isThemeDefault(kept));
        assertEquals(new Color(0x123456), kept);
        // the classic default of ANOTHER role is still a deviation for this role
        assertFalse(ChatColorSettings.isThemeDefault(ChatColorSettings.fromStored("F4F4F4", "userBackground")));
    }

    @Test
    public void onlyUserChoicesArePersisted() throws Exception {
        File file = new File(folder.getRoot(), "askai.properties");
        AppConfigurationRepository repository = new AppConfigurationRepository(file);
        repository.save(AppConfiguration.defaults()
                .withChatColors(ChatColorSettings.defaults().withUserBackground(new Color(0x123456))));
        Properties stored = new Properties();
        FileInputStream in = new FileInputStream(file);
        try {
            stored.load(in);
        } finally {
            in.close();
        }
        assertEquals("123456", stored.getProperty("chat.color.userBackground"));
        assertEquals(null, stored.getProperty("chat.color.transcriptBackground"));
        ChatColorSettings loaded = repository.load().getChatColors();
        assertEquals(new Color(0x123456), loaded.getUserBackground());
        assertTrue(ChatColorSettings.isThemeDefault(loaded.getTranscriptBackground()));
    }

    @Test
    public void resetGivesTheThemeDefaults() {
        ChatColorSettings reset = ChatColorSettings.defaults();
        assertTrue(ChatColorSettings.isThemeDefault(reset.getAssistantBackground()));
    }

    @Test
    public void withCopiesChangeOnlyTheTargetedColor() {
        ChatColorSettings changed = ChatColorSettings.defaults().withUserBackground(Color.BLACK);
        assertEquals(Color.BLACK, changed.getUserBackground());
        assertTrue(ChatColorSettings.isThemeDefault(changed.getAssistantBackground()));
        assertTrue(ChatColorSettings.isThemeDefault(changed.getTranscriptBackground()));
    }
}
