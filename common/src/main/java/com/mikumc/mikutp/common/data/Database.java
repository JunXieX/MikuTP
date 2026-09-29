package com.mikumc.mikutp.common.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC persistence for all shared state. Every method performs blocking IO and
 * must run on an async thread. Works on both SQLite and MySQL; the caller
 * supplies connections through {@link ConnectionProvider}.
 */
public final class Database implements AutoCloseable {

    public enum Dialect {
        SQLITE, MYSQL
    }

    /** Supplies pooled connections; implemented by the platform modules. */
    public interface ConnectionProvider extends AutoCloseable {
        Connection acquire() throws SQLException;

        @Override
        void close() throws Exception;
    }

    private final ConnectionProvider provider;
    private final Dialect dialect;
    private final String prefix;

    public Database(ConnectionProvider provider, Dialect dialect, String prefix) {
        this.provider = provider;
        this.dialect = dialect;
        this.prefix = prefix == null ? "" : prefix;
    }

    public Dialect dialect() {
        return dialect;
    }

    // ------------------------------------------------------------------ schema

    public void init() throws SQLException {
        if (dialect == Dialect.SQLITE) {
            exec("PRAGMA journal_mode=WAL");
            exec("PRAGMA synchronous=NORMAL");
        }
        exec("""
                CREATE TABLE IF NOT EXISTS %splayers (
                  uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                  name VARCHAR(16) NOT NULL,
                  last_online BIGINT NOT NULL,
                  tpa_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                  back_server VARCHAR(64),
                  back_world VARCHAR(64),
                  back_x DOUBLE,
                  back_y DOUBLE,
                  back_z DOUBLE,
                  back_yaw DOUBLE,
                  back_pitch DOUBLE
                )""".formatted(prefix));
        exec("""
                CREATE TABLE IF NOT EXISTS %shomes (
                  owner_uuid VARCHAR(36) NOT NULL,
                  name VARCHAR(64) NOT NULL,
                  server VARCHAR(64) NOT NULL,
                  world VARCHAR(64) NOT NULL,
                  x DOUBLE NOT NULL,
                  y DOUBLE NOT NULL,
                  z DOUBLE NOT NULL,
                  yaw DOUBLE NOT NULL DEFAULT 0,
                  pitch DOUBLE NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL,
                  PRIMARY KEY (owner_uuid, name)
                )""".formatted(prefix));
        exec("""
                CREATE TABLE IF NOT EXISTS %swarps (
                  name VARCHAR(64) NOT NULL PRIMARY KEY,
                  server VARCHAR(64) NOT NULL,
                  world VARCHAR(64) NOT NULL,
                  x DOUBLE NOT NULL,
                  y DOUBLE NOT NULL,
                  z DOUBLE NOT NULL,
                  yaw DOUBLE NOT NULL DEFAULT 0,
                  pitch DOUBLE NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL
                )""".formatted(prefix));
        exec("""
                CREATE TABLE IF NOT EXISTS %srequests (
                  id VARCHAR(36) NOT NULL PRIMARY KEY,
                  type INTEGER NOT NULL,
                  requester_uuid VARCHAR(36) NOT NULL,
                  requester_name VARCHAR(16) NOT NULL,
                  requester_server VARCHAR(64) NOT NULL,
                  target_uuid VARCHAR(36) NOT NULL,
                  target_name VARCHAR(16) NOT NULL DEFAULT '',
                  status INTEGER NOT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL
                )""".formatted(prefix));
        exec("""
                CREATE TABLE IF NOT EXISTS %steleports (
                  uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                  server VARCHAR(64) NOT NULL,
                  world VARCHAR(64) NOT NULL,
                  x DOUBLE NOT NULL,
                  y DOUBLE NOT NULL,
                  z DOUBLE NOT NULL,
                  yaw DOUBLE NOT NULL DEFAULT 0,
                  pitch DOUBLE NOT NULL DEFAULT 0,
                  source VARCHAR(16) NOT NULL,
                  created_at BIGINT NOT NULL
                )""".formatted(prefix));
        exec("""
                CREATE TABLE IF NOT EXISTS %signores (
                  blocker_uuid VARCHAR(36) NOT NULL,
                  blocked_uuid VARCHAR(36) NOT NULL,
                  expires_at BIGINT NOT NULL,
                  created_at BIGINT NOT NULL,
                  PRIMARY KEY (blocker_uuid, blocked_uuid)
                )""".formatted(prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_homes_owner ON %shomes (owner_uuid)".formatted(prefix, prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_requests_target ON %srequests (target_uuid, status)".formatted(prefix, prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_requests_requester ON %srequests (requester_uuid, status, updated_at)".formatted(prefix, prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_requests_housekeeping ON %srequests (status, created_at)".formatted(prefix, prefix));
    }

    private void index(String sql) throws SQLException {
        try {
            exec(sql);
        } catch (SQLException e) {
            // MySQL has no IF NOT EXISTS for CREATE INDEX; a duplicate index is harmless.
            if (e.getErrorCode() != 1061 && !String.valueOf(e.getMessage()).toLowerCase().contains("exist")) {
                throw e;
            }
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = provider.acquire(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    // ------------------------------------------------------------------ players

    public void upsertPlayer(String uuid, String name, long now) throws SQLException {
        String sql;
        if (dialect == Dialect.SQLITE) {
            sql = """
                    INSERT INTO %splayers (uuid, name, last_online, tpa_enabled) VALUES (?, ?, ?, 1)
                    ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_online = excluded.last_online
                    """.formatted(prefix);
        } else {
            sql = """
                    INSERT INTO %splayers (uuid, name, last_online, tpa_enabled) VALUES (?, ?, ?, 1)
                    ON DUPLICATE KEY UPDATE name = VALUES(name), last_online = VALUES(last_online)
                    """.formatted(prefix);
        }
        try (Connection c = provider.acquire(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    public Optional<PlayerProfile> getPlayer(String uuid) throws SQLException {
        return queryOne("SELECT uuid, name, last_online, tpa_enabled FROM %splayers WHERE uuid = ?".formatted(prefix),
                ps -> ps.setString(1, uuid), Database::readProfile);
    }

    public Optional<PlayerProfile> getPlayerByName(String name) throws SQLException {
        String sql = "SELECT uuid, name, last_online, tpa_enabled FROM %splayers WHERE name = ?".formatted(prefix);
        if (dialect == Dialect.SQLITE) {
            sql += " COLLATE NOCASE";
        }
        sql += " ORDER BY last_online DESC LIMIT 1";
        return queryOne(sql, ps -> ps.setString(1, name), Database::readProfile);
    }

    public void setTpaEnabled(String uuid, boolean enabled) throws SQLException {
        execUpdate("UPDATE %splayers SET tpa_enabled = ? WHERE uuid = ?".formatted(prefix),
                ps -> {
                    ps.setInt(1, enabled ? 1 : 0);
                    ps.setString(2, uuid);
                });
    }

    public void setBack(String uuid, Position pos) throws SQLException {
        execUpdate("UPDATE %splayers SET back_server = ?, back_world = ?, back_x = ?, back_y = ?, back_z = ?, back_yaw = ?, back_pitch = ? WHERE uuid = ?".formatted(prefix),
                ps -> {
                    if (pos == null) {
                        ps.setString(1, null);
                        ps.setString(2, null);
                        ps.setDouble(3, 0);
                        ps.setDouble(4, 0);
                        ps.setDouble(5, 0);
                        ps.setDouble(6, 0);
                        ps.setDouble(7, 0);
                    } else {
                        ps.setString(1, pos.server);
                        ps.setString(2, pos.world);
                        ps.setDouble(3, pos.x);
                        ps.setDouble(4, pos.y);
                        ps.setDouble(5, pos.z);
                        ps.setDouble(6, pos.yaw);
                        ps.setDouble(7, pos.pitch);
                    }
                    ps.setString(8, uuid);
                });
    }

    public Optional<Position> getBack(String uuid) throws SQLException {
        return queryOne("SELECT back_server, back_world, back_x, back_y, back_z, back_yaw, back_pitch FROM %splayers WHERE uuid = ?".formatted(prefix),
                ps -> ps.setString(1, uuid), rs -> {
                    String server = rs.getString("back_server");
                    String world = rs.getString("back_world");
                    if (server == null || world == null) {
                        return null;
                    }
                    return new Position(server, world, rs.getDouble("back_x"), rs.getDouble("back_y"),
                            rs.getDouble("back_z"), (float) rs.getDouble("back_yaw"), (float) rs.getDouble("back_pitch"));
                });
    }

    private static PlayerProfile readProfile(ResultSet rs) throws SQLException {
        return new PlayerProfile(rs.getString("uuid"), rs.getString("name"),
                rs.getLong("last_online"), rs.getInt("tpa_enabled") != 0);
    }

    // ------------------------------------------------------------------ homes

    public void saveHome(Home home) throws SQLException {
        String base = """
                INSERT INTO %shomes (owner_uuid, name, server, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(prefix);
        String sql = dialect == Dialect.SQLITE
                ? base + """
                  ON CONFLICT(owner_uuid, name) DO UPDATE SET server = excluded.server, world = excluded.world,
                    x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch"""
                : base + """
                  ON DUPLICATE KEY UPDATE server = VALUES(server), world = VALUES(world),
                    x = VALUES(x), y = VALUES(y), z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch)""";
        execUpdate(sql, ps -> writePosition(ps, home.position, 1, home.ownerUuid, home.name, home.createdAt));
    }

    public boolean deleteHome(String ownerUuid, String name) throws SQLException {
        return execUpdate("DELETE FROM %shomes WHERE owner_uuid = ? AND name = ?".formatted(prefix),
                ps -> {
                    ps.setString(1, ownerUuid);
                    ps.setString(2, name);
                }) > 0;
    }

    public Optional<Home> getHome(String ownerUuid, String name) throws SQLException {
        return queryOne("SELECT * FROM %shomes WHERE owner_uuid = ? AND name = ?".formatted(prefix),
                ps -> {
                    ps.setString(1, ownerUuid);
                    ps.setString(2, name);
                }, rs -> readHome(rs, "owner_uuid"));
    }

    public List<Home> listHomes(String ownerUuid) throws SQLException {
        return queryList("SELECT * FROM %shomes WHERE owner_uuid = ? ORDER BY name".formatted(prefix),
                ps -> ps.setString(1, ownerUuid), rs -> readHome(rs, "owner_uuid"));
    }

    public int countHomes(String ownerUuid) throws SQLException {
        return queryCount("SELECT COUNT(*) FROM %shomes WHERE owner_uuid = ?".formatted(prefix), ownerUuid);
    }

    private static Home readHome(ResultSet rs, String ownerColumn) throws SQLException {
        return new Home(rs.getString(ownerColumn), rs.getString("name"),
                readPosition(rs, "", "server"), rs.getLong("created_at"));
    }

    // ------------------------------------------------------------------ warps

    public void saveWarp(Warp warp) throws SQLException {
        String base = """
                INSERT INTO %swarps (name, server, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(prefix);
        String sql = dialect == Dialect.SQLITE
                ? base + """
                  ON CONFLICT(name) DO UPDATE SET server = excluded.server, world = excluded.world,
                    x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch"""
                : base + """
                  ON DUPLICATE KEY UPDATE server = VALUES(server), world = VALUES(world),
                    x = VALUES(x), y = VALUES(y), z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch)""";
        execUpdate(sql, ps -> writePosition(ps, warp.position, 1, warp.name, null, warp.createdAt));
    }

    public boolean deleteWarp(String name) throws SQLException {
        return execUpdate("DELETE FROM %swarps WHERE name = ?".formatted(prefix),
                ps -> ps.setString(1, name)) > 0;
    }

    public Optional<Warp> getWarp(String name) throws SQLException {
        return queryOne("SELECT * FROM %swarps WHERE name = ?".formatted(prefix),
                ps -> ps.setString(1, name), rs -> new Warp(rs.getString("name"), readPosition(rs, "", "server"), rs.getLong("created_at")));
    }

    public List<Warp> listWarps(String serverId) throws SQLException {
        return queryList("SELECT * FROM %swarps WHERE server = ? ORDER BY name".formatted(prefix),
                ps -> ps.setString(1, serverId), rs -> new Warp(rs.getString("name"), readPosition(rs, "", "server"), rs.getLong("created_at")));
    }

    // ------------------------------------------------------------------ requests

    public void insertRequest(TpRequest r) throws SQLException {
        execUpdate("""
                INSERT INTO %srequests (id, type, requester_uuid, requester_name, requester_server, target_uuid, target_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(prefix), ps -> {
            ps.setString(1, r.id);
            ps.setInt(2, r.type.id());
            ps.setString(3, r.requesterUuid);
            ps.setString(4, r.requesterName);
            ps.setString(5, r.requesterServer);
            ps.setString(6, r.targetUuid);
            ps.setString(7, r.targetName == null ? "" : r.targetName);
            ps.setInt(8, r.status.id());
            ps.setLong(9, r.createdAt);
            ps.setLong(10, r.updatedAt);
        });
    }

    public Optional<TpRequest> getRequest(String id) throws SQLException {
        return queryOne("SELECT * FROM %srequests WHERE id = ?".formatted(prefix),
                ps -> ps.setString(1, id), Database::readRequest);
    }

    public void setRequestStatus(String id, TpRequest.Status status, long now) throws SQLException {
        execUpdate("UPDATE %srequests SET status = ?, updated_at = ? WHERE id = ?".formatted(prefix),
                ps -> {
                    ps.setInt(1, status.id());
                    ps.setLong(2, now);
                    ps.setString(3, id);
                });
    }

    /** Unanswered requests delivered to any of the given players. */
    public List<TpRequest> listPendingForTargets(List<String> uuids, long minCreated) throws SQLException {
        String in = placeholders(uuids.size());
        return queryList(("SELECT * FROM %srequests WHERE status = ? AND target_uuid IN (%s) AND created_at >= ?"
                .formatted(prefix, in)), ps -> {
            ps.setInt(1, TpRequest.Status.PENDING.id());
            for (int i = 0; i < uuids.size(); i++) {
                ps.setString(i + 2, uuids.get(i));
            }
            ps.setLong(uuids.size() + 2, minCreated);
        }, Database::readRequest);
    }

    /**
     * Requests of the given players that changed state after {@code sinceUpdated}.
     * Matches both requesters (answers to own requests) and targets (requests that
     * were accepted with the target as the one who travels).
     */
    public List<TpRequest> listUpdatesSince(List<String> uuids, long sinceUpdated) throws SQLException {
        String in = placeholders(uuids.size());
        return queryList(("SELECT * FROM %srequests WHERE updated_at > ? AND status <> ? "
                + "AND (requester_uuid IN (%s) OR target_uuid IN (%s))").formatted(prefix, in, in), ps -> {
            int idx = 1;
            ps.setLong(idx++, sinceUpdated);
            ps.setInt(idx++, TpRequest.Status.PENDING.id());
            for (int i = 0; i < uuids.size(); i++) {
                ps.setString(idx++, uuids.get(i));
            }
            for (int i = 0; i < uuids.size(); i++) {
                ps.setString(idx++, uuids.get(i));
            }
        }, Database::readRequest);
    }

    public int expireStale(long now, long expiryMs) throws SQLException {
        return execUpdate("UPDATE %srequests SET status = ?, updated_at = ? WHERE status = ? AND created_at < ?".formatted(prefix),
                ps -> {
                    ps.setInt(1, TpRequest.Status.EXPIRED.id());
                    ps.setLong(2, now);
                    ps.setInt(3, TpRequest.Status.PENDING.id());
                    ps.setLong(4, now - expiryMs);
                });
    }

    public int purgeFinished(long now, long keepMs) throws SQLException {
        return execUpdate("DELETE FROM %srequests WHERE status <> ? AND created_at < ?".formatted(prefix),
                ps -> {
                    ps.setInt(1, TpRequest.Status.PENDING.id());
                    ps.setLong(2, now - keepMs);
                });
    }

    private static TpRequest readRequest(ResultSet rs) throws SQLException {
        return new TpRequest(rs.getString("id"), TpRequest.Type.byId(rs.getInt("type")),
                rs.getString("requester_uuid"), rs.getString("requester_name"), rs.getString("requester_server"),
                rs.getString("target_uuid"), rs.getString("target_name"), TpRequest.Status.byId(rs.getInt("status")),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    /** True when the player still has an unanswered outgoing request. */
    public boolean hasPendingFromRequester(String requesterUuid) throws SQLException {
        return queryOne("SELECT id FROM %srequests WHERE requester_uuid = ? AND status = ? LIMIT 1".formatted(prefix),
                ps -> {
                    ps.setString(1, requesterUuid);
                    ps.setInt(2, TpRequest.Status.PENDING.id());
                }, rs -> rs.getString("id")).isPresent();
    }

    // ------------------------------------------------------------------ pending teleports

    public void putPendingTeleport(PendingTeleport t) throws SQLException {
        String base = """
                INSERT INTO %steleports (uuid, server, world, x, y, z, yaw, pitch, source, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(prefix);
        String sql = dialect == Dialect.SQLITE
                ? base + " ON CONFLICT(uuid) DO UPDATE SET server = excluded.server, world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch, source = excluded.source, created_at = excluded.created_at"
                : base + " ON DUPLICATE KEY UPDATE server = VALUES(server), world = VALUES(world), x = VALUES(x), y = VALUES(y), z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch), source = VALUES(source), created_at = VALUES(created_at)";
        execUpdate(sql, ps -> {
            ps.setString(1, t.playerUuid);
            ps.setString(2, t.position.server == null ? "" : t.position.server);
            ps.setString(3, t.position.world);
            ps.setDouble(4, t.position.x);
            ps.setDouble(5, t.position.y);
            ps.setDouble(6, t.position.z);
            ps.setDouble(7, t.position.yaw);
            ps.setDouble(8, t.position.pitch);
            ps.setString(9, t.source.name());
            ps.setLong(10, t.createdAt);
        });
    }

    public Optional<PendingTeleport> takePendingTeleport(String playerUuid) throws SQLException {
        Optional<PendingTeleport> found = queryOne("SELECT * FROM %steleports WHERE uuid = ?".formatted(prefix),
                ps -> ps.setString(1, playerUuid), rs -> new PendingTeleport(rs.getString("uuid"),
                        readPosition(rs, "", "server"), PendingTeleport.Source.valueOf(rs.getString("source")),
                        rs.getLong("created_at")));
        if (found.isPresent()) {
            execUpdate("DELETE FROM %steleports WHERE uuid = ?".formatted(prefix),
                    ps -> ps.setString(1, playerUuid));
        }
        return found;
    }

    // ------------------------------------------------------------------ ignores

    public void addIgnore(IgnoreEntry e) throws SQLException {
        String base = """
                INSERT INTO %signores (blocker_uuid, blocked_uuid, expires_at, created_at) VALUES (?, ?, ?, ?)
                """.formatted(prefix);
        String sql = dialect == Dialect.SQLITE
                ? base + " ON CONFLICT(blocker_uuid, blocked_uuid) DO UPDATE SET expires_at = excluded.expires_at"
                : base + " ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at)";
        execUpdate(sql, ps -> {
            ps.setString(1, e.blockerUuid);
            ps.setString(2, e.blockedUuid);
            ps.setLong(3, e.expiresAt);
            ps.setLong(4, e.createdAt);
        });
    }

    public boolean clearIgnore(String blockerUuid, String blockedUuid) throws SQLException {
        return execUpdate("DELETE FROM %signores WHERE blocker_uuid = ? AND blocked_uuid = ?".formatted(prefix),
                ps -> {
                    ps.setString(1, blockerUuid);
                    ps.setString(2, blockedUuid);
                }) > 0;
    }

    public boolean isIgnored(String blockerUuid, String blockedUuid, long now) throws SQLException {
        return queryOne(("SELECT blocked_uuid FROM %signores WHERE blocker_uuid = ? AND blocked_uuid = ? "
                + "AND (expires_at = ? OR expires_at > ?)").formatted(prefix), ps -> {
            ps.setString(1, blockerUuid);
            ps.setString(2, blockedUuid);
            ps.setLong(3, IgnoreEntry.PERMANENT);
            ps.setLong(4, now);
        }, rs -> rs.getString("blocked_uuid")).isPresent();
    }

    public List<IgnoreEntry> listIgnores(String blockerUuid, long now) throws SQLException {
        return queryList(("SELECT * FROM %signores WHERE blocker_uuid = ? AND (expires_at = ? OR expires_at > ?)").formatted(prefix),
                ps -> {
                    ps.setString(1, blockerUuid);
                    ps.setLong(2, IgnoreEntry.PERMANENT);
                    ps.setLong(3, now);
                }, rs -> new IgnoreEntry(rs.getString("blocker_uuid"), rs.getString("blocked_uuid"),
                        rs.getLong("expires_at"), rs.getLong("created_at")));
    }

    // ------------------------------------------------------------------ plumbing

    private static void writePosition(PreparedStatement ps, Position pos, int start,
                                      String first, String second, long createdAt) throws SQLException {
        int i = start;
        if (first != null) {
            ps.setString(i++, first);
        }
        if (second != null) {
            ps.setString(i++, second);
        }
        ps.setString(i++, pos.server == null ? "" : pos.server);
        ps.setString(i++, pos.world);
        ps.setDouble(i++, pos.x);
        ps.setDouble(i++, pos.y);
        ps.setDouble(i++, pos.z);
        ps.setDouble(i++, pos.yaw);
        ps.setDouble(i++, pos.pitch);
        ps.setLong(i++, createdAt);
    }

    private static Position readPosition(ResultSet rs, String prefix, String serverColumn) throws SQLException {
        String server = rs.getString(serverColumn);
        return new Position(server == null || server.isBlank() ? null : server,
                rs.getString(prefix + "world"),
                rs.getDouble(prefix + "x"), rs.getDouble(prefix + "y"), rs.getDouble(prefix + "z"),
                (float) rs.getDouble(prefix + "yaw"), (float) rs.getDouble(prefix + "pitch"));
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private int execUpdate(String sql, Binder binder) throws SQLException {
        try (Connection c = provider.acquire(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        }
    }

    private <T> Optional<T> queryOne(String sql, Binder binder, RowMapper<T> mapper) throws SQLException {
        try (Connection c = provider.acquire(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    T mapped = mapper.map(rs);
                    if (mapped != null) {
                        return Optional.of(mapped);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private <T> List<T> queryList(String sql, Binder binder, RowMapper<T> mapper) throws SQLException {
        List<T> out = new ArrayList<>();
        try (Connection c = provider.acquire(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    T mapped = mapper.map(rs);
                    if (mapped != null) {
                        out.add(mapped);
                    }
                }
            }
        }
        return out;
    }

    private int queryCount(String sql, String param) throws SQLException {
        try (Connection c = provider.acquire(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public void close() throws Exception {
        provider.close();
    }
}
