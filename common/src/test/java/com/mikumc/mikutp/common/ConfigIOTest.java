package com.mikumc.mikutp.common;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.config.MikuTPConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigIOTest {

    @TempDir
    Path dir;

    @Test
    void loadsSnakeCaseKeysIntoCamelCaseFields() throws IOException {
        Path file = dir.resolve("config.yml");
        String yaml = """
                wild:
                  min_radius: 120
                  blocked_biome_tags: [is_ocean]
                commands:
                  home:
                    enabled: false
                    aliases: [goto]
                """;
        MikuTPConfig config = ConfigIO.loadOrCreate(file, yaml, MikuTPConfig.class);

        assertEquals(120, config.wild.minRadius);
        assertEquals(1, config.wild.blockedBiomeTags.size());
        assertFalse(config.commands.home.enabled);
        assertEquals("goto", config.commands.home.aliases.get(0));
        // 未出现的键回落到内置默认值
        assertEquals(5000, config.wild.maxRadius);
        assertEquals(5, config.home.defaultLimit);
        assertTrue(config.commands.wild.enabled);
    }

    @Test
    void writesBundledDefaultWhenMissing() throws IOException {
        Path file = dir.resolve("sub").resolve("config.yml");
        MikuTPConfig config = ConfigIO.loadOrCreate(file, "config_version: 3\n", MikuTPConfig.class);

        assertTrue(Files.exists(file));
        assertEquals(3, config.configVersion);
        assertEquals(5, config.home.defaultLimit);
    }

    @Test
    void malformedSectionKeepsDefaults() throws IOException {
        Path file = dir.resolve("config.yml");
        String yaml = """
                storage: not-a-section
                teleport:
                  warmup_seconds: 7
                """;
        MikuTPConfig config = ConfigIO.loadOrCreate(file, yaml, MikuTPConfig.class);

        assertEquals(7, config.teleport.warmupSeconds);
        assertEquals("mikutp_", config.storage.tablePrefix);
    }

    // ------------------------------------------------------------------ strict loader (M-04)

    /** A valid file is read without writing anything to disk. */
    @Test
    void strictLoadReadsWithoutWriting() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                teleport:
                  warmup_seconds: 7
                home:
                  default_limit: 9
                """);
        java.nio.file.attribute.FileTime before =
                Files.getLastModifiedTime(file);

        MikuTPConfig config = ConfigIO.loadStrict(file, MikuTPConfig.class);

        assertEquals(7, config.teleport.warmupSeconds);
        assertEquals(9, config.home.defaultLimit);
        assertEquals(before, Files.getLastModifiedTime(file), "strict load must not rewrite the file");
    }

    @Test
    void strictLoadRejectsMissingFile() {
        assertThrows(IOException.class,
                () -> ConfigIO.loadStrict(dir.resolve("absent.yml"), MikuTPConfig.class));
    }

    @Test
    void strictLoadRejectsSyntaxError() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "teleport:\n  warmup_seconds: [unclosed\n");
        assertThrows(IOException.class, () -> ConfigIO.loadStrict(file, MikuTPConfig.class));
    }

    /** A typo in the type of a value must not be silently replaced by the default. */
    @Test
    void strictLoadRejectsWrongType() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                teleport:
                  warmup_seconds: not-a-number
                """);
        assertThrows(IOException.class, () -> ConfigIO.loadStrict(file, MikuTPConfig.class));
    }

    @Test
    void strictLoadRejectsNonMappingRoot() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "- not\n- a mapping\n");
        assertThrows(IOException.class, () -> ConfigIO.loadStrict(file, MikuTPConfig.class));
    }

    /** The strict loader still accepts a valid subset (missing keys keep defaults). */
    @Test
    void strictLoadAcceptsPartialFile() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "home:\n  default_limit: 3\n");
        MikuTPConfig config = ConfigIO.loadStrict(file, MikuTPConfig.class);
        assertEquals(3, config.home.defaultLimit);
        assertEquals(5000, config.wild.maxRadius);
    }

    /** YAML may hand back a quoted scalar as a String; it must still be applied. */
    @Test
    void quotedNumbersAreParsed() throws IOException {
        Path file = dir.resolve("config.yml");
        String yaml = """
                teleport:
                  warmup_seconds: "7"
                  move_threshold_blocks: "1.5"
                  sounds: "false"
                home:
                  default_limit: "9"
                """;
        MikuTPConfig config = ConfigIO.loadOrCreate(file, yaml, MikuTPConfig.class);

        assertEquals(7, config.teleport.warmupSeconds);
        assertEquals(1.5, config.teleport.moveThresholdBlocks);
        assertFalse(config.teleport.sounds);
        assertEquals(9, config.home.defaultLimit);
    }
}
