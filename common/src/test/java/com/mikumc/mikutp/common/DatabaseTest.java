package com.mikumc.mikutp.common;

import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.Home;
import com.mikumc.mikutp.common.data.IgnoreEntry;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.PlayerProfile;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.data.TpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        public Connection acquire() throws SQLException {
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
        database = new Database(new PooledSqlite(), Database.Dialect.SQLITE, "mikutp_");
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

        assertTrue(database.deleteHome("uuid-a", "base"));
        assertFalse(database.deleteHome("uuid-a", "base"));
        assertEquals(0, database.countHomes("uuid-a"));
    }

    @Test
    void playerProfileAndBack() throws Exception {
        database.upsertPlayer("uuid-a", "Alice", 100L);
        database.upsertPlayer("uuid-a", "Alice_2", 200L);
        database.upsertPlayer("uuid-B", "bob", 300L);

        PlayerProfile byName = database.getPlayerByName("aLiCe_2").orElseThrow();
        assertEquals("Alice_2", byName.name);
        assertEquals(200L, byName.lastOnline);
        assertTrue(byName.tpaEnabled);

        database.setTpaEnabled("uuid-a", false);
        assertFalse(database.getPlayer("uuid-a").orElseThrow().tpaEnabled);

        Position back = new Position("s1", "world_nether", 5, 40, 5, 0f, 0f);
        database.setBack("uuid-a", back, "back_");
        assertEquals("world_nether", database.getBack("uuid-a", "back_").orElseThrow().world);
        database.setBack("uuid-a", null, "back_");
        assertTrue(database.getBack("uuid-a", "back_").isEmpty());
    }

    @Test
    void deathBackIsIndependentOfTeleportBack() throws Exception {
        database.upsertPlayer("uuid-a", "Alice", 1L);
        Position teleportBack = new Position("s1", "world", 1, 64, 2, 0f, 0f);
        Position deathBack = new Position("s2", "world", 3, 32, 4, 90f, 0f);
        database.setBack("uuid-a", teleportBack, "back_");
        database.setBack("uuid-a", deathBack, "death_");

        assertEquals(1.0, database.getBack("uuid-a", "back_").orElseThrow().x);
        assertEquals(3.0, database.getBack("uuid-a", "death_").orElseThrow().x);
        assertEquals("s2", database.getBack("uuid-a", "death_").orElseThrow().server);

        // Clearing one record must not touch the other.
        database.setBack("uuid-a", null, "death_");
        assertTrue(database.getBack("uuid-a", "death_").isEmpty());
        assertEquals("world", database.getBack("uuid-a", "back_").orElseThrow().world);
    }

    @Test
    void requestLifecycle() throws Exception {
        long now = System.currentTimeMillis();
        TpRequest request = new TpRequest(UUID.randomUUID().toString(), TpRequest.Type.GO,
                "req-uuid", "Alice", "s1", "tgt-uuid", "Bob", TpRequest.Status.PENDING, now, now);
        database.insertRequest(request);

        assertTrue(database.hasPendingFromRequester("req-uuid"));
        assertEquals(1, database.listPendingForTargets(List.of("tgt-uuid"), now - 1000).size());
        assertEquals(0, database.listPendingForTargets(List.of("tgt-uuid"), now + 1000).size());

        database.setRequestStatus(request.id, TpRequest.Status.ACCEPTED, now + 5);
        List<TpRequest> updates = database.listUpdatesSince(List.of("req-uuid", "tgt-uuid"), now - 1);
        assertEquals(1, updates.size());
        assertEquals(TpRequest.Status.ACCEPTED, updates.get(0).status);
        assertEquals("Bob", updates.get(0).targetName);

        // Request is 10s old with a 60s expiry: not stale yet. After 120s it is.
        TpRequest stale = new TpRequest(UUID.randomUUID().toString(), TpRequest.Type.GO,
                "req-uuid", "Alice", "s1", "tgt-uuid", "Bob", TpRequest.Status.PENDING, now, now);
        database.insertRequest(stale);
        assertEquals(0, database.expireStale(now + 10_000, 60_000));
        assertEquals(1, database.expireStale(now + 120_000, 60_000));
        assertEquals(0, database.expireStale(now + 120_000, 60_000));
    }

    @Test
    void pendingTeleportHandoff() throws Exception {
        Position position = new Position("s2", "world", 1, 2, 3, 0f, 0f);
        database.putPendingTeleport(new PendingTeleport("uuid-a", position, PendingTeleport.Source.TPA, 7L));
        database.putPendingTeleport(new PendingTeleport("uuid-a", new Position("s2", "world", 9, 9, 9, 0f, 0f), PendingTeleport.Source.TPA, 8L));

        PendingTeleport taken = database.takePendingTeleport("uuid-a").orElseThrow();
        assertEquals(9.0, taken.position.x);
        assertEquals("s2", taken.position.server);
        assertTrue(database.takePendingTeleport("uuid-a").isEmpty());
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
}
