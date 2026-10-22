package com.darshan.recon.ingest.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileChecksumTest {

    @TempDir
    Path folder;

    @Test
    void computesTheSha256OfTheFileContentAsLowerCaseHex() throws IOException {
        Path file = Files.writeString(folder.resolve("abc.txt"), "abc");

        assertThat(FileChecksum.sha256Hex(file))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void differentContentGivesADifferentChecksumAndSameContentTheSame() throws IOException {
        Path first = Files.writeString(folder.resolve("first.csv"), "HDR,BANK01,1,2026-10-01\n");
        Path copy = Files.writeString(folder.resolve("copy.csv"), "HDR,BANK01,1,2026-10-01\n");
        Path other = Files.writeString(folder.resolve("other.csv"), "HDR,BANK01,1,2026-10-02\n");

        assertThat(FileChecksum.sha256Hex(copy)).isEqualTo(FileChecksum.sha256Hex(first));
        assertThat(FileChecksum.sha256Hex(other)).isNotEqualTo(FileChecksum.sha256Hex(first));
    }
}
