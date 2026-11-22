package com.aresstack.askai.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class OwnerOnlyDirectoryTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void createsTheDirectoryForTheOwnerOnly() throws Exception {
        Path dir = folder.getRoot().toPath().resolve("conf").resolve("keepass");

        assertThat(OwnerOnlyDirectory.secure(dir)).isTrue();

        assertThat(Files.isDirectory(dir)).isTrue();
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    public void reportsFailureInsteadOfThrowing() throws Exception {
        Path blocker = Files.writeString(folder.getRoot().toPath().resolve("datei"), "x");

        assertThat(OwnerOnlyDirectory.secure(blocker.resolve("keepass"))).isFalse();
    }
}
