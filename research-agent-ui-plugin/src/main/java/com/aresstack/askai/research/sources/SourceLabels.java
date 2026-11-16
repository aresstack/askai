package com.aresstack.askai.research.sources;

/** The readable labels of the bounded source values: a raw enum name never reaches the user. */
public final class SourceLabels {

    private SourceLabels() {
    }

    public static String of(Object value) {
        if (value instanceof SourceStatus) {
            switch ((SourceStatus) value) {
                case PARKED: return "Not read yet";
                case NEW: return "New";
                case REVIEWED: return "Reviewed";
                case ACCEPTED: return "Accepted";
                case EXCLUDED: return "Excluded";
                case DUPLICATE: return "Duplicate";
                case SUPERSEDED: return "Superseded";
                default: break;
            }
        }
        if (value instanceof SourceRelevance) {
            switch ((SourceRelevance) value) {
                case UNKNOWN: return "Not rated";
                case LOW: return "Low";
                case MEDIUM: return "Medium";
                case HIGH: return "High";
                default: break;
            }
        }
        if (value instanceof SourceReliability) {
            switch ((SourceReliability) value) {
                case UNKNOWN: return "Not rated";
                case LOW: return "Low";
                case MEDIUM: return "Medium";
                case HIGH: return "High";
                case PRIMARY_SOURCE: return "Primary source";
                default: break;
            }
        }
        return String.valueOf(value);
    }
}
