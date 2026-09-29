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
}
