package com.mikumc.mikutp.common;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.message.MessageBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the bundled resources: a broken config or message file ships to every server. */
class BundledResourcesTest {

    @TempDir
    Path dir;

    @Test
    void bundledConfigParses() throws Exception {
        String yaml = read("/config.yml");
        MikuTPConfig config = ConfigIO.loadOrCreate(dir.resolve("config.yml"), yaml, MikuTPConfig.class);
        assertEquals(2, config.configVersion);
        assertTrue(config.sync != null);
        assertTrue(config.commands != null);
        assertTrue(config.commands.ui.enabled);
        assertTrue(config.wild.enabled);
    }

    @Test
    void bundledMessagesParse() throws Exception {
        MessageBundle bundle = new MessageBundle(read("/messages_zh_cn.json"));
        assertTrue(bundle.raw("prefix").contains("MikuTP"));
        assertTrue(bundle.has("tpa.sent"));
        assertTrue(bundle.has("wild.searching"));
        // Every key referenced by the code must exist in the bundle.
        String[] required = {
                "common.no-permission", "common.player-only", "common.player-not-found", "common.cooldown",
                "common.warmup.started", "common.warmup.countdown", "common.warmup.cancelled-move",
                "common.warmup.cancelled-damage", "common.teleporting", "common.teleported",
                "common.teleport-failed", "common.cross-connect-failed", "common.cross-disabled",
                "common.invalid-name", "common.reload-done",
                "home.limit-reached", "home.set", "home.deleted", "home.not-found", "home.empty", "home.going",
                "warp.set", "warp.deleted", "warp.not-found", "warp.empty", "warp.going",
                "tpa.sent", "tpa.usage", "tpa.sent-here", "tpahere.usage", "tpa.received-chat",
                "tpa.received-here-chat", "tpa.hint-chat", "tpa.accepted-target", "tpa.denied-target",
                "tpa.accepted-requester", "tpa.denied-requester", "tpa.blocked-requester", "tpa.blocked-target",
                "tpa.blocked-permanent-target", "tpa.no-pending", "tpa.self", "tpa.already-pending",
                "tpa.target-toggled", "tpa.expired-requester", "tpa.toggled-on", "tpa.toggled-off",
                "tpa.block-list", "tpa.block-list-empty", "tpa.unblocked", "tpa.not-blocked",
                "wild.searching", "wild.searching-done", "wild.disabled", "wild.failed",
                "back.none", "back.going", "dback.none", "dback.going",
                "outtp.usage", "outtp.none", "otp.usage", "otph.usage",
                "admin.self", "admin.target-left", "admin.pulled-you", "admin.bring-started",
                "admin.bring-all-done", "admin.resync-done", "admin.permissions", "admin.permissions-line",
                "admin.info", "admin.unknown-sub",
                "dialog.tpa.title", "dialog.tpa.hint", "dialog.tpa.input-label", "dialog.tpa.send",
                "dialog.tpa.here-send", "dialog.request.title", "dialog.request.body-go",
                "dialog.request.body-come", "dialog.request.accept", "dialog.request.deny",
                "dialog.request.block", "dialog.list.title-homes", "dialog.list.title-warps",
                "dialog.list.title-delhome", "dialog.list.title-delwarp", "dialog.list.next",
                "dialog.list.prev", "dialog.list.close", "dialog.name.title", "dialog.name.hint",
                "dialog.name.input-label", "dialog.name.confirm"
        };
        for (String key : required) {
            assertTrue(bundle.has(key), "missing message key: " + key);
        }
    }

    private String read(String resource) throws Exception {
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("bundled resource missing: " + resource);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
