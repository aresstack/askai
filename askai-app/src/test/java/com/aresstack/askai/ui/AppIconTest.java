package com.aresstack.askai.ui;

import org.junit.Test;

import java.awt.Image;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class AppIconTest {

    @Test
    public void theBundledIconLoadsInEveryWindowSize() {
        assertEquals(9, AppIcon.load(AppIcon.RESOURCE).size());
    }

    @Test
    public void sizedPicksTheSmallestVariantThatIsAtLeastAsLarge() {
        List<Image> images = AppIcon.images();
        assertFalse(images.isEmpty());

        assertSame(images.get(0), AppIcon.sized(16));
        assertSame(images.get(3), AppIcon.sized(32));
        assertSame(images.get(3), AppIcon.sized(30));
        assertSame("larger than all variants falls back to the largest",
                images.get(images.size() - 1), AppIcon.sized(1024));
    }
}
