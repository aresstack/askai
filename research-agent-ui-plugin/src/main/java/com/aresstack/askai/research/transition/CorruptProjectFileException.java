package com.aresstack.askai.research.transition;

import java.io.File;

/** A project file exists but cannot be read; the caller keeps it and blocks instead of starting over. */
public final class CorruptProjectFileException extends Exception {

    private final File file;

    public CorruptProjectFileException(File file, String message) {
        super(message);
        this.file = file;
    }

    public File getFile() {
        return file;
    }
}
