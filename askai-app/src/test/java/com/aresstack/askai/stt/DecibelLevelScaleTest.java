package com.aresstack.askai.stt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The dB level scale: quiet speech spreads over the display instead of huddling at the bottom,
 * loud knocks stop dwarfing everything — the whole point of going logarithmic.
 */
public class DecibelLevelScaleTest {

    @Test
    public void quietSoundsBecomeVisibleAndLoudOnesStopDominating() {
        int whisper = DecibelLevelScale.percentFromPeak(328);   // 1% linear ≈ -40 dB
        int speech = DecibelLevelScale.percentFromPeak(3277);   // 10% linear ≈ -20 dB
        int knock = DecibelLevelScale.percentFromPeak(32767);   // full scale = 0 dB
        assertEquals("a -40 dB whisper sits at a THIRD of the display, not at 1 percent",
                33, whisper);
        assertEquals("-20 dB speech reaches two thirds", 67, speech);
        assertEquals(100, knock);
        assertTrue("the knock is no longer 100x the whisper", knock < whisper * 4);
    }

    @Test
    public void edgesAreExact() {
        assertEquals(0, DecibelLevelScale.percentFromPeak(0));
        assertEquals("below the -60 dB floor is 0", 0, DecibelLevelScale.percentFromPeak(20));
        assertEquals(100, DecibelLevelScale.percentFromPeak(Short.MAX_VALUE));
    }

    @Test
    public void theLinearMigrationKeepsThePhysicalLevel() {
        assertEquals("the old linear default 8 is the SAME loudness as dB 63",
                63, DecibelLevelScale.dbPercentFromLinearPercent(8));
        assertEquals(0, DecibelLevelScale.dbPercentFromLinearPercent(0));
        assertEquals("linear full scale clamps to the settable maximum",
                95, DecibelLevelScale.dbPercentFromLinearPercent(100));
    }
}
