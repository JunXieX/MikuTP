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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    /** Single pooled connection, mirroring the production SQLite setup. */
    private static final class PooledSqlite implements Database.ConnectionProvider {
        private final com.zaxxer.hikari.HikariDataSource dataSource;

        PooledSqlite() {
            com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl("jdbc:sqlite::memory:");
            config.setMaximumPoolSize(1);
            this.dataSource = new com.zaxxer.hikari.HikariDataSource(config);
        }

        @Override
        public Connection acquire() throws java.sql.SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }

    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(new PooledSqlite(), "mikutp_");
        database.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void homeCrud() throws Exception {
        Position position = new Position("s1", "world", 10.5, 64, -3.25, 90f, 0f);
        database.saveHome(new Home("uuid-a", "base", position, 42L));
        database.saveHome(new Home("uuid-a", "base", new Position("s1", "world", 1, 1, 1, 0f, 0f), 43L));

        assertEquals(1, database.countHomes("uuid-a"));
        assertEquals(1, database.listHomes("uuid-a").size());
        assertEquals(1.0, database.getHome("uuid-a", "base").orElseThrow().position.x);
        assertEquals(1, database.listAllHomes().size());

        assertTrue(database.deleteHome("uuid-a", "base"));
        assertFalse(database.deleteHome("uuid-a", "base"));
        assertEquals(0, database.countHomes("uuid-a"));
    }

    @Test
    void warpCrudIsServerScoped() throws Exception {
        database.saveWarp(new Warp("shop", new Position("s1", "world", 1, 64, 1, 0f, 0f), 1L));
        database.saveWarp(new Warp("market", new Position("s2", "world", 2, 64, 2, 0f, 0f), 2L));

        assertEquals(1, database.listWarps("s1").size());
        assertEquals("market", database.listWarps("s2").get(0).name);
        assertTrue(database.deleteWarp("shop"));
        assertEquals(0, database.listWarps("s1").size());
    }

    @Test
    void playerProfileAndNameLookup() throws Exception {
        database.upsertPlayer("uuid-a", "Alice", 100L);
        database.upsertPlayer("uuid-a", "Alice_2", 200L);
        database.upsertPlayer("uuid-B", "bob", 300L);

        PlayerProfile byName = database.getPlayerByName("aLiCe_2").orElseThrow();
        assertEquals("Alice_2", byName.name);
        assertEquals(200L, byName.lastOnline);
        assertTrue(byName.tpaEnabled);

        database.setTpaEnabled("uuid-a", false);
        assertFalse(database.getPlayer("uuid-a").orElseThrow().tpaEnabled);
        assertEquals(2, database.listAllProfiles().size());
    }

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
        assertEquals("s1", database.listAllStoredPositions().get(0).death().server);
    }

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
            tx.deleteHome("uuid-a", "base");
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
