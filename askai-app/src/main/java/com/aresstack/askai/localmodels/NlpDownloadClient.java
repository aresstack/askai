package com.aresstack.askai.localmodels;

import java.io.IOException;

/** Fetches the full bytes of a curated artifact URL (following redirects). Isolated so the installer is testable. */
public interface NlpDownloadClient {

    /** @return the complete artifact bytes. @throws IOException on any transport error or a non-200 final status. */
    byte[] fetch(String url) throws IOException;
}
