package com.mikumc.mikutp.common.data;

import com.mikumc.mikutp.common.sync.SyncBus.OutboxRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Local SQLite persistence. Every backend owns its full copy of homes,
 * profiles, ignore lists and return positions; cross-server convergence is
 * handled by the sync bus, never by this class.
 */
public final class Database implements AutoCloseable {

    /** Column prefixes of the three stored return positions. */
    public static final String BACK_PREFIX = "back_";
    public static final String DEATH_PREFIX = "death_";
    public static final String LOGOUT_PREFIX = "logout_";

    /** Supplies pooled connections; implemented by the platform modules. */
    public interface ConnectionProvider extends AutoCloseable {
        Connection acquire() throws SQLException;

        @Override
        void close() throws Exception;
    }

    private final ConnectionProvider provider;
    private final String prefix;

    public Database(ConnectionProvider provider, String prefix) {
        this.provider = provider;
        this.prefix = prefix == null ? "" : prefix;
    }

    // ------------------------------------------------------------------ schema

    public void init() throws SQLException {
        exec("PRAGMA journal_mode=WAL");
        exec("PRAGMA synchronous=NORMAL");
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
                  back_pitch DOUBLE,
                  death_server VARCHAR(64),
                  death_world VARCHAR(64),
                  death_x DOUBLE,
                  death_y DOUBLE,
                  death_z DOUBLE,
                  death_yaw DOUBLE,
                  death_pitch DOUBLE,
                  logout_server VARCHAR(64),
                  logout_world VARCHAR(64),
                  logout_x DOUBLE,
                  logout_y DOUBLE,
                  logout_z DOUBLE,
                  logout_yaw DOUBLE,
                  logout_pitch DOUBLE
                )""".formatted(prefix));
        // Tables created by older versions: add newer columns one by one, ignoring
        // "duplicate column" failures (MySQL 1060 / SQLite duplicate column message).
        column("ALTER TABLE %splayers ADD COLUMN death_server VARCHAR(64)".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN death_world VARCHAR(64)".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN death_x DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN death_y DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN death_z DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN death_yaw DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN death_pitch DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN logout_server VARCHAR(64)".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN logout_world VARCHAR(64)".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN logout_x DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN logout_y DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN logout_z DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN logout_yaw DOUBLE".formatted(prefix));
        column("ALTER TABLE %splayers ADD COLUMN logout_pitch DOUBLE".formatted(prefix));
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
                CREATE TABLE IF NOT EXISTS %signores (
                  blocker_uuid VARCHAR(36) NOT NULL,
                  blocked_uuid VARCHAR(36) NOT NULL,
                  expires_at BIGINT NOT NULL,
                  created_at BIGINT NOT NULL,
                  PRIMARY KEY (blocker_uuid, blocked_uuid)
                )""".formatted(prefix));
        exec("""
                CREATE TABLE IF NOT EXISTS %soutbox (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  payload TEXT NOT NULL
                )""".formatted(prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_homes_owner ON %shomes (owner_uuid)".formatted(prefix, prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_players_name ON %splayers (name COLLATE NOCASE)".formatted(prefix, prefix));
    }

    private void index(String sql) throws SQLException {
        try {
            exec(sql);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).toLowerCase().contains("exist")) {
                throw e;
            }
        }
    }

    private void column(String sql) throws SQLException {
        try {
            exec(sql);
        } catch (SQLException e) {
            String message = String.valueOf(e.getMessage()).toLowerCase();
            if (!message.contains("duplicate column") && !message.contains("already exists")) {
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
        execUpdate("""
                INSERT INTO %splayers (uuid, name, last_online, tpa_enabled) VALUES (?, ?, ?, 1)
                ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_online = excluded.last_online
                """.formatted(prefix), ps -> {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, now);
        });
    }

    public Optional<PlayerProfile> getPlayer(String uuid) throws SQLException {
        return queryOne("SELECT uuid, name, last_online, tpa_enabled FROM %splayers WHERE uuid = ?".formatted(prefix),
                ps -> ps.setString(1, uuid), Database::readProfile);
    }

    public Optional<PlayerProfile> getPlayerByName(String name) throws SQLException {
        return queryOne(("SELECT uuid, name, last_online, tpa_enabled FROM %splayers WHERE name = ? COLLATE NOCASE "
                + "ORDER BY last_online DESC LIMIT 1").formatted(prefix),
                ps -> ps.setString(1, name), Database::readProfile);
    }

    public void setTpaEnabled(String uuid, boolean enabled) throws SQLException {
        execUpdate("UPDATE %splayers SET tpa_enabled = ? WHERE uuid = ?".formatted(prefix),
                ps -> {
                    ps.setInt(1, enabled ? 1 : 0);
                    ps.setString(2, uuid);
                });
    }

    private static PlayerProfile readProfile(ResultSet rs) throws SQLException {
        return new PlayerProfile(rs.getString("uuid"), rs.getString("name"),
                rs.getLong("last_online"), rs.getInt("tpa_enabled") != 0);
    }

    /** Every known profile on this server, for resync dumps. */
    public List<PlayerProfile> listAllProfiles() throws SQLException {
        return queryList("SELECT uuid, name, last_online, tpa_enabled FROM %splayers".formatted(prefix),
                Database::readProfile);
    }

    // ------------------------------------------------------------------ stored positions (back / death / logout)

    /** Stores a return position under the given column prefix; {@code null} clears it. */
    public void setPosition(String uuid, Position pos, String colPrefix) throws SQLException {
        // Replicated positions may arrive before the player's profile row does.
        execUpdate("""
                INSERT INTO %splayers (uuid, name, last_online, tpa_enabled) VALUES (?, '', ?, 1)
                ON CONFLICT(uuid) DO NOTHING
                """.formatted(prefix), ps -> {
            ps.setString(1, uuid);
            ps.setLong(2, System.currentTimeMillis());
        });
        execUpdate(("UPDATE %splayers SET " + colPrefix + "server = ?, " + colPrefix + "world = ?, "
                + colPrefix + "x = ?, " + colPrefix + "y = ?, " + colPrefix + "z = ?, "
                + colPrefix + "yaw = ?, " + colPrefix + "pitch = ? WHERE uuid = ?").formatted(prefix),
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

    public Optional<Position> getPosition(String uuid, String colPrefix) throws SQLException {
        return queryOne(("SELECT " + colPrefix + "server, " + colPrefix + "world, " + colPrefix + "x, "
                + colPrefix + "y, " + colPrefix + "z, " + colPrefix + "yaw, " + colPrefix + "pitch"
                + " FROM %splayers WHERE uuid = ?").formatted(prefix),
                ps -> ps.setString(1, uuid), rs -> readPrefixedPosition(rs, colPrefix));
    }

    private static Position readPrefixedPosition(ResultSet rs, String colPrefix) throws SQLException {
        String server = rs.getString(colPrefix + "server");
        String world = rs.getString(colPrefix + "world");
        if (server == null || world == null) {
            return null;
        }
        return new Position(server, world, rs.getDouble(colPrefix + "x"), rs.getDouble(colPrefix + "y"),
                rs.getDouble(colPrefix + "z"), (float) rs.getDouble(colPrefix + "yaw"),
                (float) rs.getDouble(colPrefix + "pitch"));
    }

    /** Every stored return position on this server, for resync dumps. */
    public List<StoredPositions> listAllStoredPositions() throws SQLException {
        return queryList(("SELECT uuid, back_server, back_world, back_x, back_y, back_z, back_yaw, back_pitch, "
                + "death_server, death_world, death_x, death_y, death_z, death_yaw, death_pitch "
                + "FROM %splayers WHERE back_server IS NOT NULL OR death_server IS NOT NULL").formatted(prefix),
                rs -> new StoredPositions(rs.getString("uuid"),
                        readPrefixedPosition(rs, BACK_PREFIX), readPrefixedPosition(rs, DEATH_PREFIX)));
    }

    public record StoredPositions(String uuid, Position back, Position death) {
    }

    // ------------------------------------------------------------------ homes

    public void saveHome(Home home) throws SQLException {
        execUpdate("""
                INSERT INTO %shomes (owner_uuid, name, server, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_uuid, name) DO UPDATE SET server = excluded.server, world = excluded.world,
                  x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch
                """.formatted(prefix), ps -> {
            ps.setString(1, home.ownerUuid);
            ps.setString(2, home.name);
            ps.setString(3, home.position.server == null ? "" : home.position.server);
            ps.setString(4, home.position.world);
            ps.setDouble(5, home.position.x);
            ps.setDouble(6, home.position.y);
            ps.setDouble(7, home.position.z);
            ps.setDouble(8, home.position.yaw);
            ps.setDouble(9, home.position.pitch);
            ps.setLong(10, home.createdAt);
        });
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
                }, rs -> new Home(rs.getString("owner_uuid"), rs.getString("name"),
                        readPosition(rs), rs.getLong("created_at")));
    }

    public List<Home> listHomes(String ownerUuid) throws SQLException {
        return queryList("SELECT * FROM %shomes WHERE owner_uuid = ? ORDER BY name".formatted(prefix),
                ps -> ps.setString(1, ownerUuid), rs -> new Home(rs.getString("owner_uuid"),
                        rs.getString("name"), readPosition(rs), rs.getLong("created_at")));
    }

    public List<Home> listAllHomes() throws SQLException {
        return queryList("SELECT * FROM %shomes".formatted(prefix),
                rs -> new Home(rs.getString("owner_uuid"), rs.getString("name"),
                        readPosition(rs), rs.getLong("created_at")));
    }

    public int countHomes(String ownerUuid) throws SQLException {
        return queryCount("SELECT COUNT(*) FROM %shomes WHERE owner_uuid = ?".formatted(prefix), ownerUuid);
    }

    private static Position readPosition(ResultSet rs) throws SQLException {
        String server = rs.getString("server");
        return new Position(server == null || server.isBlank() ? null : server,
                rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                (float) rs.getDouble("yaw"), (float) rs.getDouble("pitch"));
    }

    // ------------------------------------------------------------------ warps

    public void saveWarp(Warp warp) throws SQLException {
        execUpdate("""
                INSERT INTO %swarps (name, server, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET server = excluded.server, world = excluded.world,
                  x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch
                """.formatted(prefix), ps -> {
            ps.setString(1, warp.name);
            ps.setString(2, warp.position.server == null ? "" : warp.position.server);
            ps.setString(3, warp.position.world);
            ps.setDouble(4, warp.position.x);
            ps.setDouble(5, warp.position.y);
            ps.setDouble(6, warp.position.z);
            ps.setDouble(7, warp.position.yaw);
            ps.setDouble(8, warp.position.pitch);
            ps.setLong(9, warp.createdAt);
        });
    }

    public boolean deleteWarp(String name) throws SQLException {
        return execUpdate("DELETE FROM %swarps WHERE name = ?".formatted(prefix),
                ps -> ps.setString(1, name)) > 0;
    }

    public List<Warp> listWarps(String serverId) throws SQLException {
        return queryList("SELECT * FROM %swarps WHERE server = ? ORDER BY name".formatted(prefix),
                ps -> ps.setString(1, serverId), rs -> new Warp(rs.getString("name"),
                        readPosition(rs), rs.getLong("created_at")));
    }

    // ------------------------------------------------------------------ ignores

    public void addIgnore(IgnoreEntry e) throws SQLException {
        execUpdate("""
                INSERT INTO %signores (blocker_uuid, blocked_uuid, expires_at, created_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(blocker_uuid, blocked_uuid) DO UPDATE SET expires_at = excluded.expires_at
                """.formatted(prefix), ps -> {
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

    public List<IgnoreEntry> listAllIgnores() throws SQLException {
        return queryList("SELECT * FROM %signores".formatted(prefix),
                rs -> new IgnoreEntry(rs.getString("blocker_uuid"), rs.getString("blocked_uuid"),
                        rs.getLong("expires_at"), rs.getLong("created_at")));
    }

    // ------------------------------------------------------------------ outbox (events waiting for the sync bus)

    public long addOutboxEvent(String payload) throws SQLException {
        try (Connection c = provider.acquire();
             PreparedStatement ps = c.prepareStatement("INSERT INTO %soutbox (payload) VALUES (?)".formatted(prefix),
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, payload);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public List<OutboxRow> takeOutboxEvents(int max) throws SQLException {
        return queryList("SELECT seq, payload FROM %soutbox ORDER BY seq LIMIT %d"
                        .formatted(prefix, Math.max(1, max)),
                rs -> new OutboxRow(rs.getLong("seq"), rs.getString("payload")));
    }

    public void deleteOutboxEvents(List<Long> seqs) throws SQLException {
        if (seqs.isEmpty()) {
            return;
        }
        try (Connection c = provider.acquire();
             PreparedStatement ps = c.prepareStatement("DELETE FROM %soutbox WHERE seq = ?".formatted(prefix))) {
            for (Long seq : seqs) {
                ps.setLong(1, seq);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------ plumbing

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

    private <T> List<T> queryList(String sql, RowMapper<T> mapper) throws SQLException {
        List<T> out = new ArrayList<>();
        try (Connection c = provider.acquire(); PreparedStatement ps = c.prepareStatement(sql)) {
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
