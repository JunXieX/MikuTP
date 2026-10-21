package com.mikumc.mikutp.common;

import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.Home;
import com.mikumc.mikutp.common.data.IgnoreEntry;
import com.mikumc.mikutp.common.data.PlayerProfile;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.data.Warp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    /**
     * Single pooled connection over a shared-cache in-memory database. Every
     * instance gets its own database name so tests (and the in-place migration
     * test) cannot observe each other's schema.
     */
    private static final class PooledSqlite implements Database.ConnectionProvider {
        private static final java.util.concurrent.atomic.AtomicInteger SEQ =
                new java.util.concurrent.atomic.AtomicInteger();

        private final com.zaxxer.hikari.HikariDataSource dataSource;

        PooledSqlite() {
            com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            // A bare ":memory:" gives every connection its own private database,
            // so a pooled test would silently run against an empty schema. The
            // shared-cache URI keeps all connections on one in-memory database.
            config.setJdbcUrl("jdbc:sqlite:file:mikutp-test-" + SEQ.incrementAndGet()
                    + "?mode=memory&cache=shared");
            config.setMaximumPoolSize(1);
            this.dataSource = new com.zaxxer.hikari.HikariDataSource(config);
        }

        @Override
        public Connection acquire() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }

    private PooledSqlite provider;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        provider = new PooledSqlite();
        database = new Database(provider, "mikutp_");
        database.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    // ------------------------------------------------------------------ schema

    @Test
    void initEnablesWalOrSharedMemoryJournal() throws Exception {
        try (Connection c = provider.acquire();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next(), "journal_mode must be readable");
            String mode = String.valueOf(rs.getString(1)).toLowerCase(java.util.Locale.ROOT);
            // File databases must end up on WAL; the shared-cache in-memory test
            // database legitimately reports "memory" (WAL is not supported there).
            assertTrue(mode.equals("wal") || mode.equals("memory"), "unexpected journal mode: " + mode);
        }
    }

    @Test
    void legacyPlayerTableIsMigratedInPlace() throws Exception {
        PooledSqlite legacyProvider = new PooledSqlite();
        try (Connection c = legacyProvider.acquire(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE mikutp_players (uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + " name VARCHAR(16) NOT NULL, last_online BIGINT NOT NULL,"
                    + " tpa_enabled BOOLEAN NOT NULL DEFAULT TRUE)");
            st.execute("INSERT INTO mikutp_players (uuid, name, last_online, tpa_enabled)"
                    + " VALUES ('old-uuid', 'Old', 5, 0)");
        }
        Database legacy = new Database(legacyProvider, "mikutp_");
        try {
            legacy.init();
            PlayerProfile profile = legacy.getPlayer("old-uuid").orElseThrow();
            assertEquals("Old", profile.name);
            assertFalse(profile.tpaEnabled);
            // Migrated columns default to "unknown"/"no version".
            assertFalse(profile.tpaKnown);
            assertEquals(0L, legacy.getPositionUpdatedAt("old-uuid", Database.BACK_PREFIX));

            // The migrated stamp column is usable right away.
            assertTrue(legacy.setPositionIfNewer("old-uuid",
                    new Position("s1", "world", 1, 2, 3, 0f, 0f), Database.BACK_PREFIX, 1234L));
            assertEquals(1234L, legacy.getPositionUpdatedAt("old-uuid", Database.BACK_PREFIX));
            assertEquals(1234L, legacy.listAllStoredPositions().get(0).backUpdatedAt());
        } finally {
            legacy.close();
        }
    }

    // ------------------------------------------------------------------ homes

    @Test
    void homeCrud() throws Exception {
        Position position = new Position("s1", "world", 10.5, 64, -3.25, 90f, 0f);
        assertTrue(database.saveHome(new Home("uuid-a", "base", position, 42L)));
        assertTrue(database.saveHome(new Home("uuid-a", "base",
                new Position("s1", "world", 1, 1, 1, 0f, 0f), 43L)));

        assertEquals(1, database.countHomes("uuid-a"));
        assertEquals(1, database.listHomes("uuid-a").size());
        assertEquals(1.0, database.getHome("uuid-a", "base").orElseThrow().position.x);
        assertEquals(1, database.listAllHomes().size());

        assertTrue(database.deleteHome("uuid-a", "base"));
        assertFalse(database.deleteHome("uuid-a", "base"));
        assertEquals(0, database.countHomes("uuid-a"));
    }

    /** M-01: a stale case variant must never delete the newer legacy row. */
    @Test
    void staleCaseVariantCannotDeleteNewerHome() throws Exception {
        assertTrue(database.saveHome(new Home("uuid-a", "Base",
                new Position("s1", "world", 1, 1, 1, 0f, 0f), 1_000L)));

        // An older replicated snapshot with different casing is dropped entirely.
        assertFalse(database.saveHome(new Home("uuid-a", "base",
                new Position("s1", "world", 9, 9, 9, 0f, 0f), 999L)));
        assertEquals(1, database.countHomes("uuid-a"));
        assertEquals("Base", database.listHomes("uuid-a").get(0).name);
        assertEquals(1.0, database.listHomes("uuid-a").get(0).position.x);
    }

    /** A newer snapshot still replaces the legacy case variant (no duplicates left). */
    @Test
    void newerSnapshotReplacesLegacyCaseVariant() throws Exception {
        assertTrue(database.saveHome(new Home("uuid-a", "Base",
                new Position("s1", "world", 1, 1, 1, 0f, 0f), 1_000L)));
        assertTrue(database.saveHome(new Home("uuid-a", "base",
                new Position("s1", "world", 2, 2, 2, 0f, 0f), 2_000L)));

        assertEquals(1, database.countHomes("uuid-a"));
        assertEquals("base", database.listHomes("uuid-a").get(0).name);
        assertEquals(2.0, database.getHome("uuid-a", "BASE").orElseThrow().position.x);
    }

    /** M-01: saveHome reports snapshots that the tombstone guard rejected. */
    @Test
    void saveHomeReportsRejectedSnapshot() throws Exception {
        long created = System.currentTimeMillis() - 60_000;
        assertTrue(database.saveHome(new Home("uuid-a", "base",
                new Position("s1", "world", 1, 1, 1, 0f, 0f), created)));
        assertTrue(database.deleteHome("uuid-a", "base"));

        // Replaying the old snapshot is rejected and reported as not written.
        assertFalse(database.saveHome(new Home("uuid-a", "base",
                new Position("s1", "world", 1, 1, 1, 0f, 0f), created)));
        assertTrue(database.getHome("uuid-a", "base").isEmpty());
    }

    /** A stale snapshot must not resurrect a row deleted after it was made. */
    @Test
    void tombstoneBlocksOlderSnapshot() throws Exception {
        long created = System.currentTimeMillis() - 60_000;
        database.saveHome(new Home("uuid-a", "base", new Position("s1", "world", 1, 1, 1, 0f, 0f), created));
        assertTrue(database.deleteHome("uuid-a", "base"));

        database.saveHome(new Home("uuid-a", "base", new Position("s1", "world", 1, 1, 1, 0f, 0f), created));
        assertTrue(database.getHome("uuid-a", "base").isEmpty());

        // A genuinely new /sethome after the delete still wins.
        database.saveHome(new Home("uuid-a", "base",
                new Position("s1", "world", 2, 2, 2, 0f, 0f), System.currentTimeMillis()));
        assertEquals(2.0, database.getHome("uuid-a", "base").orElseThrow().position.x);
    }

    @Test
    void staleTombstonesAreHiddenAndFreshOnesListed() throws Exception {
        long ancient = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000;
        try (Connection c = provider.acquire();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mikutp_tombstones (kind, owner, name, deleted_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, "home");
            ps.setString(2, "uuid-a");
            ps.setString(3, "ancient");
            ps.setLong(4, ancient);
            ps.executeUpdate();
        }
        // Older than the TTL: no longer part of the replicated state.
        assertTrue(database.listTombstones().isEmpty());

        database.deleteHome("uuid-a", "fresh");
        assertEquals(1, database.listTombstones().size());
        assertEquals("fresh", database.listTombstones().get(0).name());
    }

    // ------------------------------------------------------------------ warps

    @Test
    void warpCrudIsServerScoped() throws Exception {
        database.saveWarp(new Warp("shop", new Position("s1", "world", 1, 64, 1, 0f, 0f), 1L));
        database.saveWarp(new Warp("market", new Position("s2", "world", 2, 64, 2, 0f, 0f), 2L));

        assertEquals(1, database.listWarps("s1").size());
        assertEquals("market", database.listWarps("s2").get(0).name);
        assertTrue(database.deleteWarp("shop"));
        assertEquals(0, database.listWarps("s1").size());
    }

    // ------------------------------------------------------------------ profiles

    @Test
    void playerProfileAndNameLookup() throws Exception {
        database.upsertPlayer("uuid-a", "Alice", 100L);
        database.upsertPlayer("uuid-a", "Alice_2", 200L);
        database.upsertPlayer("uuid-B", "bob", 300L);

        PlayerProfile byName = database.getPlayerByName("aLiCe_2").orElseThrow();
        assertEquals("Alice_2", byName.name);
        assertEquals(200L, byName.lastOnline);
        assertTrue(byName.tpaEnabled);
        assertFalse(byName.tpaKnown, "a plain identity upsert must not claim the toggle is known");

        database.setTpaEnabled("uuid-a", false);
        PlayerProfile toggled = database.getPlayer("uuid-a").orElseThrow();
        assertFalse(toggled.tpaEnabled);
        assertTrue(toggled.tpaKnown);
        assertEquals(2, database.listAllProfiles().size());
        assertTrue(database.listAllProfiles().stream().anyMatch(p -> p.tpaKnown));
    }

    /** M-03: an identity refresh must never reset tpa_known or the stored toggle. */
    @Test
    void identityUpsertNeverClearsTpaKnown() throws Exception {
        database.upsertPlayer("uuid-a", "Alice", 100L);
        database.setTpaEnabled("uuid-a", false);
        assertTrue(database.getPlayer("uuid-a").orElseThrow().tpaKnown);

        // Older identity snapshot arrives late: it may not touch the toggle state.
        database.upsertPlayer("uuid-a", "Alice", 50L);
        PlayerProfile profile = database.getPlayer("uuid-a").orElseThrow();
        assertFalse(profile.tpaEnabled);
        assertTrue(profile.tpaKnown);
    }

    /** Replicated profiles are last-writer-wins by timestamp. */
    @Test
    void staleProfileSnapshotCannotUndoToggle() throws Exception {
        database.upsertPlayer("uuid-a", "Alice", 1_000L);
        database.runTransaction(tx -> tx.setTpaEnabled("uuid-a", false, 2_000L));
        assertFalse(database.getPlayer("uuid-a").orElseThrow().tpaEnabled);

        database.runTransaction(tx -> {
            tx.upsertPlayer("uuid-a", "Alice", 1_000L);
            tx.setTpaEnabled("uuid-a", true, 1_000L);
        });
        assertFalse(database.getPlayer("uuid-a").orElseThrow().tpaEnabled);
    }

    // ------------------------------------------------------------------ positions

    @Test
    void storedPositionsAreIndependent() throws Exception {
        database.upsertPlayer("uuid-a", "Alice", 1L);
        Position back = new Position("s1", "world_nether", 5, 40, 5, 0f, 0f);
        Position death = new Position("s2", "world", 3, 32, 4, 90f, 0f);
        Position logout = new Position("s1", "world", 7, 65, 8, 180f, 0f);

        database.setPosition("uuid-a", back, Database.BACK_PREFIX);
        database.setPosition("uuid-a", death, Database.DEATH_PREFIX);
        database.setPosition("uuid-a", logout, Database.LOGOUT_PREFIX);

        assertEquals(5.0, database.getPosition("uuid-a", Database.BACK_PREFIX).orElseThrow().x);
        assertEquals(3.0, database.getPosition("uuid-a", Database.DEATH_PREFIX).orElseThrow().x);
        assertEquals(7.0, database.getPosition("uuid-a", Database.LOGOUT_PREFIX).orElseThrow().x);
        assertEquals("s2", database.getPosition("uuid-a", Database.DEATH_PREFIX).orElseThrow().server);

        // Clearing one record must not touch the others.
        database.setPosition("uuid-a", null, Database.DEATH_PREFIX);
        assertTrue(database.getPosition("uuid-a", Database.DEATH_PREFIX).isEmpty());
        assertEquals("world_nether", database.getPosition("uuid-a", Database.BACK_PREFIX).orElseThrow().world);
        assertEquals("world", database.getPosition("uuid-a", Database.LOGOUT_PREFIX).orElseThrow().world);
    }

    /** M-02: replicated positions are last-writer-wins by version stamp. */
    @Test
    void positionWritesAreVersioned() throws Exception {
        assertTrue(database.setPositionIfNewer("uuid-a",
                new Position("s1", "world", 1, 1, 1, 0f, 0f), Database.BACK_PREFIX, 2_000L));
        assertEquals(2_000L, database.getPositionUpdatedAt("uuid-a", Database.BACK_PREFIX));

        // A stale snapshot is rejected and does not overwrite the newer position.
        assertFalse(database.setPositionIfNewer("uuid-a",
                new Position("s1", "world", 9, 9, 9, 0f, 0f), Database.BACK_PREFIX, 1_000L));
        assertEquals(1.0, database.getPosition("uuid-a", Database.BACK_PREFIX).orElseThrow().x);
        assertEquals(2_000L, database.getPositionUpdatedAt("uuid-a", Database.BACK_PREFIX));

        // An equal stamp still wins (resending the newest snapshot is harmless).
        assertTrue(database.setPositionIfNewer("uuid-a",
                new Position("s1", "world", 2, 2, 2, 0f, 0f), Database.BACK_PREFIX, 2_000L));
        assertEquals(2.0, database.getPosition("uuid-a", Database.BACK_PREFIX).orElseThrow().x);

        // A newer snapshot wins.
        assertTrue(database.setPositionIfNewer("uuid-a",
                new Position("s1", "world", 3, 3, 3, 0f, 0f), Database.BACK_PREFIX, 3_000L));
        assertEquals(3.0, database.getPosition("uuid-a", Database.BACK_PREFIX).orElseThrow().x);
    }

    /** M-02: the stamps are per position kind; a death update must not age out /back. */
    @Test
    void positionStampsAreIndependentPerKind() throws Exception {
        assertTrue(database.setPositionIfNewer("uuid-a",
                new Position("s1", "world", 1, 1, 1, 0f, 0f), Database.BACK_PREFIX, 2_000L));
        assertTrue(database.setPositionIfNewer("uuid-a",
                new Position("s2", "world", 5, 5, 5, 0f, 0f), Database.DEATH_PREFIX, 5_000L));

        assertEquals(2_000L, database.getPositionUpdatedAt("uuid-a", Database.BACK_PREFIX));
        assertEquals(5_000L, database.getPositionUpdatedAt("uuid-a", Database.DEATH_PREFIX));

        // A back update between those two stamps is still accepted.
        assertTrue(database.setPositionIfNewer("uuid-a",
                new Position("s1", "world", 2, 2, 2, 0f, 0f), Database.BACK_PREFIX, 3_000L));
        assertEquals(2.0, database.getPosition("uuid-a", Database.BACK_PREFIX).orElseThrow().x);

        Database.StoredPositions stored = database.listAllStoredPositions().get(0);
        assertEquals(3_000L, stored.backUpdatedAt());
        assertEquals(5_000L, stored.deathUpdatedAt());
    }

    /** A position arriving before the profile must not leak an empty-name profile into dumps. */
    @Test
    void positionPlaceholderIsNotDumpedAsProfile() throws Exception {
        assertTrue(database.setPositionIfNewer("uuid-a",
                new Position("s1", "world", 1, 2, 3, 0f, 0f), Database.BACK_PREFIX, 1_000L));
        // The row exists (identity for the position) but no profile info is known.
        assertTrue(database.getPlayer("uuid-a").isPresent());
        assertTrue(database.listAllProfiles().isEmpty());
        assertTrue(database.listAllStoredPositions().stream().anyMatch(s -> "uuid-a".equals(s.uuid())));

        // A real profile upsert fills the identity in and it becomes dumpable.
        database.upsertPlayer("uuid-a", "Alice", 2_000L);
        assertEquals(1, database.listAllProfiles().size());
        assertEquals("Alice", database.listAllProfiles().get(0).name);
    }

    @Test
    void unsupportedPositionPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> database.setPosition("uuid-a", null, "nope_"));
        assertThrows(IllegalArgumentException.class,
                () -> database.getPosition("uuid-a", "back_updated_at; DROP TABLE x"));
        assertThrows(IllegalArgumentException.class,
                () -> database.getPositionUpdatedAt("uuid-a", ""));
    }

    @Test
    void resyncDumpSeesEverything() throws Exception {
        database.upsertPlayer("uuid-a", "Alice", 10L);
        database.saveHome(new Home("uuid-a", "base", new Position("s1", "world", 1, 1, 1, 0f, 0f), 1L));
        database.setPosition("uuid-a", new Position("s1", "world", 2, 2, 2, 0f, 0f), Database.DEATH_PREFIX);
        database.addIgnore(new IgnoreEntry("uuid-a", "uuid-b", IgnoreEntry.PERMANENT, 1L));

        assertEquals(1, database.listAllProfiles().size());
        assertEquals(1, database.listAllHomes().size());
        assertEquals(1, database.listAllStoredPositions().size());
        assertEquals(1, database.listAllIgnores().size());
        Database.StoredPositions stored = database.listAllStoredPositions().get(0);
        assertEquals("s1", stored.death().server);
        assertTrue(stored.deathUpdatedAt() > 0L);
        assertEquals(0L, stored.backUpdatedAt());
    }

    // ------------------------------------------------------------------ ignores

    @Test
    void ignoreEntries() throws Exception {
        long now = System.currentTimeMillis();
        database.addIgnore(new IgnoreEntry("tgt-uuid", "req-uuid", now + 60_000, now));
        assertTrue(database.isIgnored("tgt-uuid", "req-uuid", now));
        assertEquals(1, database.listIgnores("tgt-uuid", now).size());

        database.addIgnore(new IgnoreEntry("tgt-uuid", "req-uuid", now - 1_000, now));
        assertFalse(database.isIgnored("tgt-uuid", "req-uuid", now));

        database.addIgnore(new IgnoreEntry("tgt-uuid", "req-uuid", IgnoreEntry.PERMANENT, now));
        assertTrue(database.isIgnored("tgt-uuid", "req-uuid", now + 999_999_999));
        assertTrue(database.clearIgnore("tgt-uuid", "req-uuid"));
        assertFalse(database.isIgnored("tgt-uuid", "req-uuid", now));
    }

    // ------------------------------------------------------------------ transactions

    /** Replicated state is applied through one transaction, preserving arrival order. */
    @Test
    void transactionAppliesManyWritesInOrder() throws Exception {
        database.runTransaction(tx -> {
            tx.upsertPlayer("uuid-a", "Alice", 10L);
            tx.saveHome(new Home("uuid-a", "base", new Position("s1", "world", 1, 1, 1, 0f, 0f), 1L));
            tx.saveHome(new Home("uuid-a", "farm", new Position("s1", "world", 2, 2, 2, 0f, 0f), 2L));
            tx.setPosition("uuid-a", new Position("s1", "world", 3, 3, 3, 0f, 0f), Database.BACK_PREFIX);
            tx.addIgnore(new IgnoreEntry("uuid-a", "uuid-b", IgnoreEntry.PERMANENT, 1L));
        });

        assertEquals("Alice", database.getPlayer("uuid-a").orElseThrow().name);
        assertEquals(2, database.countHomes("uuid-a"));
        assertEquals(3.0, database.getPosition("uuid-a", Database.BACK_PREFIX).orElseThrow().x);
        assertTrue(database.isIgnored("uuid-a", "uuid-b", System.currentTimeMillis()));

        // A delete queued after the set wins: order within the batch is kept.
        database.runTransaction(tx -> {
            tx.saveHome(new Home("uuid-a", "base", new Position("s1", "world", 9, 9, 9, 0f, 0f), 3L));
            tx.deleteHome("uuid-a", "base", System.currentTimeMillis());
        });
        assertTrue(database.getHome("uuid-a", "base").isEmpty());
    }

    @Test
    void transactionRollsBackOnFailure() throws Exception {
        assertThrows(java.sql.SQLException.class, () -> database.runTransaction(tx -> {
            tx.upsertPlayer("uuid-x", "Ghost", 1L);
            throw new java.sql.SQLException("boom");
        }));
        assertTrue(database.getPlayer("uuid-x").isEmpty());
    }

    /** m-02: a nested pooled-connection acquire would block until the pool timeout. */
    @Test
    void nestedDatabaseCallInsideTransactionFailsFast() {
        assertThrows(IllegalStateException.class, () -> database.runTransaction(tx -> {
            database.getPlayer("uuid-a");
        }));
        assertThrows(IllegalStateException.class, () -> database.runTransaction(tx -> {
            database.saveHome(new Home("uuid-a", "base",
                    new Position("s1", "world", 1, 1, 1, 0f, 0f), 1L));
        }));
        // The guard is released again, so the next transaction works normally.
        assertTrue(database.getPlayer("missing-uuid").isEmpty());
        database.runTransaction(tx -> tx.upsertPlayer("uuid-b", "Bob", 1L));
        assertEquals("Bob", database.getPlayer("uuid-b").orElseThrow().name);
    }

    @Test
    void rejectsUnsafeTablePrefix() {
        Database.ConnectionProvider unused = new Database.ConnectionProvider() {
            @Override
            public Connection acquire() {
                throw new UnsupportedOperationException("no connection expected");
            }

            @Override
            public void close() {
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new Database(unused, "bad prefix;"));
        assertThrows(IllegalArgumentException.class, () -> new Database(unused, "players --"));
        // Letters, digits and underscores are the documented shape.
        new Database(unused, "mikutp_2");
    }
}
