package com.aresstack.askai.ui.about;

import java.io.IOException;
import java.util.List;

/** The third-party libraries of the build, for the About screen. */
public interface OssLicenseRepository {

    List<OssLicenseEntry> findAll() throws IOException;
}
