package com.mikumc.mikutp.common;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.util.Validate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MikuTPConfigTest {

    /** Defaults must be internally consistent, otherwise a fresh install is broken. */
    @Test
    void defaultConfigHasNoValidationProblems() {
        assertEquals(List.of(), new MikuTPConfig().validationProblems());
    }

    @Test
    void validationProblemsDetectBrokenValues() {
        MikuTPConfig config = new MikuTPConfig();
        config.home.namePattern = "[a-z";
        config.wild.minRadius = 10_000;
        config.wild.maxRadius = 100;
        config.sync.mode = "VELOCITY";
        config.sync.token = "";
        config.crossServer.serverId = "  ";
        config.languageFile = "";
        config.tpa.requestExpirySeconds = 0;

        List<String> problems = config.validationProblems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("name_pattern")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("min_radius")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("sync.token")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("server_id")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("language_file")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("request_expiry_seconds")), problems.toString());
    }

    /**
     * copyFrom is a hand-written field list: a new top-level field that is not
     * copied would silently not apply on reload. This test fails in that case.
     */
    /** A shared origin id makes cross-server peers discard each other's events. */
    @Test
    void validationWarnsAboutDefaultServerIdInCrossServerMode() {
        MikuTPConfig config = new MikuTPConfig();
        config.sync.mode = "VELOCITY";
        config.sync.token = "token";
        // Default id "server": unique ids are mandatory once sync is on.
        assertTrue(config.validationProblems().stream()
                        .anyMatch(p -> p.contains("server_id")), config.validationProblems().toString());

        config.crossServer.serverId = "survival";
        assertEquals(List.of(), config.validationProblems());
    }

    @Test
    void copyFromCoversEveryTopLevelField() throws Exception {
        Set<String> declared = new HashSet<>();
        for (Field field : MikuTPConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            declared.add(field.getName());
        }
        assertFalse(declared.isEmpty());

        MikuTPConfig source = new MikuTPConfig();
        // Give every top-level field a value that differs from the defaults so a
        // missing copy is detectable.
        source.configVersion = -1;
        source.storage = new MikuTPConfig.Storage();
        source.storage.tablePrefix = "copied_";
        source.crossServer = new MikuTPConfig.CrossServer();
        source.crossServer.serverId = "copied";
        source.sync = new MikuTPConfig.Sync();
        source.sync.mode = "VELOCITY";
        source.sync.token = "token";
        source.teleport = new MikuTPConfig.Teleport();
        source.teleport.warmupSeconds = 11;
        source.home = new MikuTPConfig.Home();
        source.home.defaultLimit = 13;
        source.warp = new MikuTPConfig.Warp();
        source.warp.enabled = false;
        source.tpa = new MikuTPConfig.Tpa();
        source.tpa.requestExpirySeconds = 17;
        source.back = new MikuTPConfig.Back();
        source.back.enabled = false;
        source.wild = new MikuTPConfig.Wild();
        source.wild.maxRadius = 19;
        source.dialogs = new MikuTPConfig.Dialogs();
        source.dialogs.listPageSize = 23;
        source.commands = new MikuTPConfig.Commands();
        source.commands.home.enabled = false;
        source.languageFile = "copied.json";
        source.parsePlaceholderApi = false;

        MikuTPConfig target = new MikuTPConfig();
        target.copyFrom(source);

        for (String name : declared) {
            Field field = MikuTPConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            Object expected = field.get(source);
            Object actual = field.get(target);
            assertEquals(expected, actual, "copyFrom does not copy the field: " + name);
        }
    }

    @Test
    void validateRecognisesUsablePatterns() {
        assertTrue(Validate.isValidPattern("[a-zA-Z0-9_-]{1,32}"));
        assertFalse(Validate.isValidPattern("[a-"));
        assertFalse(Validate.isValidPattern(""));
        assertFalse(Validate.isValidPattern(null));
    }

    @Test
    void validateNameMatchesWholeInput() {
        assertTrue(Validate.validName("[a-z]{1,4}", "base"));
        assertFalse(Validate.validName("[a-z]{1,4}", "base2"));
        assertFalse(Validate.validName("[a-z]{1,4}", ""));
        assertFalse(Validate.validName("[a-", "base"));
    }
}
