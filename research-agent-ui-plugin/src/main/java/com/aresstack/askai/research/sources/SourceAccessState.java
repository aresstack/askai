package com.aresstack.askai.research.sources;

/**
 * Whether a website demanded a manual security check (CAPTCHA / "verify you are human") while the research
 * read its pages. Shown in the Sources list so a protected — often relevant — source is never silently lost.
 */
public enum SourceAccessState {
    /** No security check was recorded for the website. */
    NONE,
    /** A security check was demanded and never solved: pages of this website may be missing from the corpus. */
    BLOCKED,
    /** A security check was demanded and later solved (the page was read afterwards). Worth a second look. */
    RESOLVED
}
