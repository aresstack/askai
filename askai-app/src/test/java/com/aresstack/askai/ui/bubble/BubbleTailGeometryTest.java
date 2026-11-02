package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import java.awt.geom.PathIterator;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The assistant Markdown bubble's tail must point toward the transcript center, following the
 * {@link BubbleSide} contract, not a hardcoded direction. A left-side bubble points right; a right-side
 * bubble points left. Guards the fix for the assistant bubble that used to point at the outer window edge.
 */
public class BubbleTailGeometryTest {

    private static final int WIDTH = 240;
    private static final int HEIGHT = 90;

    @Test
    public void leftSideBubblePointsRightTowardCenter() {
        double tipX = tipX(AssistantMarkdownBubble.buildTail(BubbleSide.LEFT, WIDTH, HEIGHT));
        assertEquals("tip sits on the right inner edge", WIDTH - 1, tipX, 0.001);
        assertTrue("tail points inward (right), not to the left window edge", tipX > WIDTH / 2.0);
    }

    @Test
    public void rightSideBubblePointsLeftTowardCenter() {
        double tipX = tipX(AssistantMarkdownBubble.buildTail(BubbleSide.RIGHT, WIDTH, HEIGHT));
        assertEquals("tip sits on the left inner edge", 1, tipX, 0.001);
        assertTrue("tail points inward (left)", tipX < WIDTH / 2.0);
    }

    /** The tail is moveTo(base), lineTo(tip), lineTo(base); the tip is the middle vertex. */
    private static double tipX(Path2D tail) {
        List<double[]> points = new ArrayList<double[]>();
        double[] coords = new double[6];
        for (PathIterator it = tail.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(coords);
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                points.add(new double[]{coords[0], coords[1]});
            }
        }
        return points.get(1)[0];
    }
}
