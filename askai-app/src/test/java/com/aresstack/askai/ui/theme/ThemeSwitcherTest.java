package com.aresstack.askai.ui.theme;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.UIManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ThemeSwitcherTest {

    private AskAiTheme before;
    private Object buttonUi;
    private Object scrollBarUi;

    @Before
    public void remember() {
        before = AskAiTheme.active();
        buttonUi = UIManager.get("ButtonUI");
        scrollBarUi = UIManager.get("ScrollBarUI");
    }

    @After
    public void restore() {
        AskAiTheme.setActive(before);
        UIManager.put("ButtonUI", buttonUi);
        UIManager.put("ScrollBarUI", scrollBarUi);
    }

    @Test
    public void applyActivatesReinstallsAndFiresExactlyOnce() {
        final int[] calls = {0};
        final String[] seen = {null};
        Runnable listener = new Runnable() {
            @Override
            public void run() {
                calls[0]++;
                seen[0] = AskAiTheme.active().name() + "/" + UIManager.getColor("Panel.background").getRGB();
            }
        };
        AskAiTheme.addChangeListener(listener);
        try {
            AskAiTheme classic = AskAiTheme.classic();
            ThemeSwitcher.apply(classic);
            assertSame(classic, AskAiTheme.active());
            assertEquals(classic.comicPalette().getSurface(), UIManager.getColor("Panel.background"));
            assertEquals(1, calls[0]);
            assertEquals("listeners see the fully applied theme",
                    "classic/" + classic.comicPalette().getSurface().getRGB(), seen[0]);

            AskAiTheme arcane = AskAiTheme.arcane();
            ThemeSwitcher.apply(arcane);
            assertEquals(arcane.comicPalette().getSurface(), UIManager.getColor("Panel.background"));
            assertEquals(2, calls[0]);
        } finally {
            AskAiTheme.removeChangeListener(listener);
        }
    }

    @Test
    public void startValuePrecedenceIsPropertyThenConfigThenDefault() {
        assertEquals("classic", ThemeSwitcher.startTheme("classic", "arcane").name());
        assertEquals("arcane", ThemeSwitcher.startTheme(" ARCANE ", "classic").name());
        assertEquals("classic", ThemeSwitcher.startTheme(null, "classic").name());
        assertEquals("classic", ThemeSwitcher.startTheme("garbage", "classic").name());
        assertEquals("classic", ThemeSwitcher.startTheme("", "classic").name());
        assertEquals("arcane", ThemeSwitcher.startTheme(null, null).name());
        assertEquals("arcane", ThemeSwitcher.startTheme(null, "").name());
        assertEquals("arcane", ThemeSwitcher.startTheme("x", "y").name());
    }

    @Test
    public void comboEntriesMapToThemes() {
        assertEquals("Arcane Knowledge", ThemeSwitcher.displayNames()[0]);
        assertEquals("Classic Comic", ThemeSwitcher.displayNames()[1]);
        assertEquals("arcane", ThemeSwitcher.themeAt(0).name());
        assertEquals("classic", ThemeSwitcher.themeAt(1).name());
        assertEquals(1, ThemeSwitcher.indexOf(AskAiTheme.classic()));
        assertEquals(0, ThemeSwitcher.indexOf(AskAiTheme.arcane()));
    }
}
