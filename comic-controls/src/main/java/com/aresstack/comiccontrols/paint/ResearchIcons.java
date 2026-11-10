package com.aresstack.comiccontrols.paint;

import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * The line glyphs of the research sidebar tabs (Sources, Outline, Document): the actions there are icon
 * buttons with a tooltip, never text buttons, so they fit a narrow sidebar. Same geometry rules as
 * {@link ComposerIcons}: 15×15, drawn with the owning component's foreground, no icon assets and no font
 * glyphs (a missing Unicode glyph would render as an empty box).
 */
public final class ResearchIcons {

    private ResearchIcons() {
    }

    /** Two overlapping sheets (copy to the clipboard). */
    public static StrokeIcon copy() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(5, 5, 8, 8, 2, 2);
                g2.drawPolyline(new int[]{10, 10, 2, 2, 5}, new int[]{5, 2, 2, 10, 10}, 5);
            }
        };
    }

    /** A five-pointed star outline (mark as relevant). */
    public static StrokeIcon star() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(starPath());
            }
        };
    }

    /** The filled star (marked as relevant). */
    public static StrokeIcon starFilled() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D star = starPath();
                g2.fill(star);
                g2.draw(star);
            }
        };
    }

    private static Path2D starPath() {
        Path2D star = new Path2D.Double();
        double center = 7.5;
        for (int point = 0; point < 10; point++) {
            double radius = point % 2 == 0 ? 6.0 : 2.6;
            double angle = Math.PI / 5 * point - Math.PI / 2;
            double x = center + radius * Math.cos(angle);
            double y = center + 0.4 + radius * Math.sin(angle);
            if (point == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        star.closePath();
        return star;
    }

    /** A circle with a diagonal bar (exclude). */
    public static StrokeIcon ban() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(new Ellipse2D.Double(2, 2, 11, 11));
                g2.draw(new Line2D.Double(3.8, 3.8, 11.2, 11.2));
            }
        };
    }

    /** A waste bin (delete). */
    public static StrokeIcon trash() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 4, 13, 4);
                g2.drawLine(6, 2, 9, 2);
                g2.drawPolyline(new int[]{3, 4, 11, 12}, new int[]{4, 13, 13, 4}, 4);
                g2.drawLine(6, 7, 6, 10);
                g2.drawLine(9, 7, 9, 10);
            }
        };
    }

    /** An arrow pointing up (move up). */
    public static StrokeIcon arrowUp() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(7, 13, 7, 2);
                g2.drawPolyline(new int[]{3, 7, 11}, new int[]{6, 2, 6}, 3);
            }
        };
    }

    /** An arrow pointing down (move down). */
    public static StrokeIcon arrowDown() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(7, 2, 7, 13);
                g2.drawPolyline(new int[]{3, 7, 11}, new int[]{9, 13, 9}, 3);
            }
        };
    }

    /** Text lines pushed right by an arrow (indent: make it a section). */
    public static StrokeIcon indent() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 3, 13, 3);
                g2.drawLine(8, 7, 13, 7);
                g2.drawLine(2, 12, 13, 12);
                g2.drawLine(2, 7, 5, 7);
                g2.drawPolyline(new int[]{4, 6, 4}, new int[]{5, 7, 9}, 3);
            }
        };
    }

    /** Text lines pulled left by an arrow (outdent: make it a chapter). */
    public static StrokeIcon outdent() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 3, 13, 3);
                g2.drawLine(8, 7, 13, 7);
                g2.drawLine(2, 12, 13, 12);
                g2.drawLine(2, 7, 6, 7);
                g2.drawPolyline(new int[]{4, 2, 4}, new int[]{5, 7, 9}, 3);
            }
        };
    }

    /** A bulleted list (the search plan). */
    public static StrokeIcon list() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                for (int y = 3; y <= 11; y += 4) {
                    g2.fill(new Ellipse2D.Double(1.5, y - 1, 2, 2));
                    g2.drawLine(6, y, 13, y);
                }
            }
        };
    }

    /** A four-pointed spark (generate). */
    public static StrokeIcon sparkle() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D spark = new Path2D.Double();
                spark.moveTo(7.5, 1.5);
                spark.quadTo(7.5, 7.5, 13.5, 7.5);
                spark.quadTo(7.5, 7.5, 7.5, 13.5);
                spark.quadTo(7.5, 7.5, 1.5, 7.5);
                spark.quadTo(7.5, 7.5, 7.5, 1.5);
                g2.fill(spark);
                g2.draw(spark);
            }
        };
    }

    /** A light bulb (add the suggested chapter). */
    public static StrokeIcon lightBulb() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(new Arc2D.Double(3, 1.5, 9, 9, -50, 280, Arc2D.OPEN));
                g2.drawLine(6, 10, 9, 10);
                g2.drawLine(6, 12, 9, 12);
                g2.drawLine(7, 14, 8, 14);
            }
        };
    }

    /** Curly braces (the raw JSON). */
    public static StrokeIcon braces() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[]{6, 4, 4, 2, 4, 4, 6}, new int[]{2, 3, 6, 7, 8, 12, 13}, 7);
                g2.drawPolyline(new int[]{9, 11, 11, 13, 11, 11, 9}, new int[]{2, 3, 6, 7, 8, 12, 13}, 7);
            }
        };
    }

    /** A small hierarchy (the tree view). */
    public static StrokeIcon tree() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRect(2, 2, 4, 3);
                g2.drawRect(9, 6, 4, 3);
                g2.drawRect(9, 11, 4, 2);
                g2.drawPolyline(new int[]{4, 4, 9}, new int[]{5, 12, 12}, 3);
                g2.drawLine(4, 7, 9, 7);
            }
        };
    }

    /** A counter-clockwise arrow (restore, undo). */
    public static StrokeIcon undo() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(new Arc2D.Double(2.5, 2.5, 10, 10, 120, -280, Arc2D.OPEN));
                g2.drawLine(5, 1, 5, 4);
                g2.drawLine(5, 4, 2, 4);
            }
        };
    }

    /** An eye (view, preview). */
    public static StrokeIcon eye() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D lids = new Path2D.Double();
                lids.moveTo(1.5, 7.5);
                lids.quadTo(7.5, 1.5, 13.5, 7.5);
                lids.quadTo(7.5, 13.5, 1.5, 7.5);
                g2.draw(lids);
                g2.fill(new Ellipse2D.Double(5.8, 5.8, 3.4, 3.4));
            }
        };
    }

    /** A check mark (apply, accept). */
    public static StrokeIcon check() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[]{3, 6, 12}, new int[]{8, 11, 4}, 3);
            }
        };
    }

    /** A plus beside an indented line (add a section below a chapter). */
    public static StrokeIcon addIndented() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 3, 10, 3);
                g2.drawLine(5, 7, 8, 7);
                g2.drawLine(11, 8, 11, 14);
                g2.drawLine(8, 11, 14, 11);
                g2.drawLine(5, 11, 6, 11);
            }
        };
    }

    /** A triangle running into a bar (skip). */
    public static StrokeIcon skip() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                int[] x = {3, 10, 3};
                int[] y = {3, 7, 12};
                g2.fillPolygon(x, y, 3);
                g2.drawPolygon(x, y, 3);
                g2.drawLine(12, 3, 12, 12);
            }
        };
    }

    /** The return arrow (confirm the field's text). */
    public static StrokeIcon enter() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[]{12, 12, 3}, new int[]{3, 9, 9}, 3);
                g2.drawPolyline(new int[]{6, 3, 6}, new int[]{6, 9, 12}, 3);
            }
        };
    }
}
