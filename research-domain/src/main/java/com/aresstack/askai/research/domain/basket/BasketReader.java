package com.aresstack.askai.research.domain.basket;

import java.util.List;

/**
 * Reads the passages of a source in the basket from the stock that owns it (the research stock of Ask AI, the index of
 * Space Harvester). The basket holds references only; this port is how the writing gets the text.
 */
public interface BasketReader {

    /** One passage of a source: its id, the heading path above it and its text. */
    final class Passage {
        private final String passageId;
        private final String heading;
        private final String text;

        public Passage(String passageId, String heading, String text) {
            this.passageId = passageId == null ? "" : passageId.trim();
            this.heading = heading == null ? "" : heading.trim();
            this.text = text == null ? "" : text;
            if (this.passageId.isEmpty()) {
                throw new IllegalArgumentException("a passage needs an id");
            }
        }

        public String getPassageId() {
            return passageId;
        }

        public String getHeading() {
            return heading;
        }

        public String getText() {
            return text;
        }
    }

    /** The passages of one source in reading order; empty when the stock has none. */
    List<Passage> passages(SourceBasket.SourceRef source);
}
