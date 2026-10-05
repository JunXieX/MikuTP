package com.mikumc.mikutp.common.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local SQLite persistence. Every backend owns its full copy of homes,
 * profiles, ignore lists and return positions; cross-server convergence is
 * handled by the sync bus, never by this class.
 *
 * <p>{@link #transaction} runs many writes over a single pooled connection and
 * one commit, which is how replicated state is applied in bulk.
 */
public final class Database implements AutoCloseable {

    /** Column prefixes of the three stored return positions. */
    public static final String BACK_PREFIX = "back_";
    public static final String DEATH_PREFIX = "death_";
    public static final String LOGOUT_PREFIX = "logout_";

    private static final Pattern TABLE_PREFIX = Pattern.compile("[a-zA-Z0-9_]*");

    /** Supplies pooled connections; implemented by the platform modules. */
    public interface ConnectionProvider extends AutoCloseable {
        Connection acquire() throws SQLException;

        @Override
        void close() throws Exception;
    }

    /** Write operations bound to one connection, only valid inside a {@link #transaction}. */
    public interface Tx {
        int saveHome(Home home) throws SQLException;

        int deleteHome(String ownerUuid, String name) throws SQLException;

        int upsertPlayer(String uuid, String name, long now) throws SQLException;

        int setTpaEnabled(String uuid, boolean enabled) throws SQLException;

        /** Stores a return position under {@code colPrefix}; a null position clears it. */
        int setPosition(String uuid, Position pos, String colPrefix) throws SQLException;

        int addIgnore(IgnoreEntry entry) throws SQLException;

        int clearIgnore(String blockerUuid, String blockedUuid) throws SQLException;
    }

    /** Transaction body producing a result. */
    @FunctionalInterface
    public interface TxWork<T> {
        T run(Tx tx) throws SQLException;
    }

    /** Transaction body run for its side effects. */
    @FunctionalInterface
    public interface TxAction {
        void run(Tx tx) throws SQLException;
    }

    private final ConnectionProvider provider;
    private final String prefix;

    public Database(ConnectionProvider provider, String prefix) {
        if (prefix != null && !TABLE_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid storage.table_prefix (letters, digits and '_' only): " + prefix);
        }
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
        // Tables created by older versions: add whichever newer columns are missing.
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("death_server", "VARCHAR(64)");
        expected.put("death_world", "VARCHAR(64)");
        expected.put("death_x", "DOUBLE");
        expected.put("death_y", "DOUBLE");
        expected.put("death_z", "DOUBLE");
        expected.put("death_yaw", "DOUBLE");
        expected.put("death_pitch", "DOUBLE");
        expected.put("logout_server", "VARCHAR(64)");
        expected.put("logout_world", "VARCHAR(64)");
        expected.put("logout_x", "DOUBLE");
        expected.put("logout_y", "DOUBLE");
        expected.put("logout_z", "DOUBLE");
        expected.put("logout_yaw", "DOUBLE");
        expected.put("logout_pitch", "DOUBLE");
        addMissingColumns(prefix + "players", expected);
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
        index("CREATE INDEX IF NOT EXISTS %sidx_homes_owner ON %shomes (owner_uuid)".formatted(prefix, prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_players_name ON %splayers (name COLLATE NOCASE)".formatted(prefix, prefix));
    }

    private void index(String sql) throws SQLException {
        try {
            exec(sql);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("exist")) {
                throw e;
            }
        }
    }

    /** Adds only the columns the live table does not already have (no exception churn). */
    private void addMissingColumns(String table, Map<String, String> expected) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Connection c = provider.acquire();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        for (Map.Entry<String, String> column : expected.entrySet()) {
            if (!existing.contains(column.getKey().toLowerCase(Locale.ROOT))) {
                exec("ALTER TABLE " + table + " ADD COLUMN " + column.getKey() + " " + column.getValue());
            }
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = provider.acquire(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    // ------------------------------------------------------------------ transactions

    /** Runs {@code work} on one connection inside a single commit. */
    public <T> T transaction(TxWork<T> work) throws SQLException {
        try (Connection c = provider.acquire()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(new TxImpl(c));
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    /** {@link #transaction} for side-effecting work. */
    public void runTransaction(TxAction action) throws SQLException {
        transaction(tx -> {
            action.run(tx);
            return null;
        });
    }

    private final class TxImpl implements Tx {

        private final Connection connection;

        TxImpl(Connection connection) {
            this.connection = connection;
        }

        @Override
        public int saveHome(Home home) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO %shomes (owner_uuid, name, server, world, x, y, z, yaw, pitch, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(owner_uuid, name) DO UPDATE SET server = excluded.server, world = excluded.world,
                      x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch
                    """.formatted(prefix))) {
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
                return ps.executeUpdate();
            }
        }

        @Override
        public int deleteHome(String ownerUuid, String name) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM %shomes WHERE owner_uuid = ? AND name = ?".formatted(prefix))) {
                ps.setString(1, ownerUuid);
                ps.setString(2, name);
                return ps.executeUpdate();
            }
        }

        @Override
        public int upsertPlayer(String uuid, String name, long now) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO %splayers (uuid, name, last_online, tpa_enabled) VALUES (?, ?, ?, 1)
                    ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_online = excluded.last_online
                    """.formatted(prefix))) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setLong(3, now);
                return ps.executeUpdate();
            }
        }

        @Override
        public int setTpaEnabled(String uuid, boolean enabled) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE %splayers SET tpa_enabled = ? WHERE uuid = ?".formatted(prefix))) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setString(2, uuid);
                return ps.executeUpdate();
            }
        }

        @Override
        public int setPosition(String uuid, Position pos, String colPrefix) throws SQLException {
            // One statement instead of the old INSERT-then-UPDATE pair: replicated
            // positions may arrive before the player's profile row does.
            String sql = ("INSERT INTO %splayers (uuid, name, last_online, tpa_enabled, "
                    + colPrefix + "server, " + colPrefix + "world, " + colPrefix + "x, "
                    + colPrefix + "y, " + colPrefix + "z, " + colPrefix + "yaw, " + colPrefix + "pitch) "
                    + "VALUES (?, '', ?, 1, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET "
                    + colPrefix + "server = excluded." + colPrefix + "server, "
                    + colPrefix + "world = excluded." + colPrefix + "world, "
                    + colPrefix + "x = excluded." + colPrefix + "x, "
                    + colPrefix + "y = excluded." + colPrefix + "y, "
                    + colPrefix + "z = excluded." + colPrefix + "z, "
                    + colPrefix + "yaw = excluded." + colPrefix + "yaw, "
                    + colPrefix + "pitch = excluded." + colPrefix + "pitch").formatted(prefix);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.setLong(2, System.currentTimeMillis());
                if (pos == null) {
                    ps.setString(3, null);
                    ps.setString(4, null);
                    ps.setDouble(5, 0);
                    ps.setDouble(6, 0);
                    ps.setDouble(7, 0);
                    ps.setDouble(8, 0);
                    ps.setDouble(9, 0);
                } else {
                    ps.setString(3, pos.server);
                    ps.setString(4, pos.world);
                    ps.setDouble(5, pos.x);
                    ps.setDouble(6, pos.y);
                    ps.setDouble(7, pos.z);
                    ps.setDouble(8, pos.yaw);
                    ps.setDouble(9, pos.pitch);
                }
                return ps.executeUpdate();
            }
        }

        @Override
        public int addIgnore(IgnoreEntry e) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO %signores (blocker_uuid, blocked_uuid, expires_at, created_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(blocker_uuid, blocked_uuid) DO UPDATE SET expires_at = excluded.expires_at
                    """.formatted(prefix))) {
                ps.setString(1, e.blockerUuid);
                ps.setString(2, e.blockedUuid);
                ps.setLong(3, e.expiresAt);
                ps.setLong(4, e.createdAt);
                return ps.executeUpdate();
            }
        }

        @Override
        public int clearIgnore(String blockerUuid, String blockedUuid) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM %signores WHERE blocker_uuid = ? AND blocked_uuid = ?".formatted(prefix))) {
                ps.setString(1, blockerUuid);
                ps.setString(2, blockedUuid);
                return ps.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------ players

    public void upsertPlayer(String uuid, String name, long now) throws SQLException {
        runTransaction(tx -> tx.upsertPlayer(uuid, name, now));
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
        runTransaction(tx -> tx.setTpaEnabled(uuid, enabled));
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
        runTransaction(tx -> tx.setPosition(uuid, pos, colPrefix));
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
        runTransaction(tx -> tx.saveHome(home));
    }

    public boolean deleteHome(String ownerUuid, String name) throws SQLException {
        return transaction(tx -> tx.deleteHome(ownerUuid, name)) > 0;
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
        try (Connection c = provider.acquire(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO %swarps (name, server, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET server = excluded.server, world = excluded.world,
                  x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch
                """.formatted(prefix))) {
            ps.setString(1, warp.name);
            ps.setString(2, warp.position.server == null ? "" : warp.position.server);
            ps.setString(3, warp.position.world);
            ps.setDouble(4, warp.position.x);
            ps.setDouble(5, warp.position.y);
            ps.setDouble(6, warp.position.z);
            ps.setDouble(7, warp.position.yaw);
            ps.setDouble(8, warp.position.pitch);
            ps.setLong(9, warp.createdAt);
            ps.executeUpdate();
        }
    }

    public boolean deleteWarp(String name) throws SQLException {
        try (Connection c = provider.acquire();
             PreparedStatement ps = c.prepareStatement("DELETE FROM %swarps WHERE name = ?".formatted(prefix))) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        }
    }

    public List<Warp> listWarps(String serverId) throws SQLException {
        return queryList("SELECT * FROM %swarps WHERE server = ? ORDER BY name".formatted(prefix),
                ps -> ps.setString(1, serverId), rs -> new Warp(rs.getString("name"),
                        readPosition(rs), rs.getLong("created_at")));
    }

    // ------------------------------------------------------------------ ignores

    public void addIgnore(IgnoreEntry e) throws SQLException {
        runTransaction(tx -> tx.addIgnore(e));
    }

    public boolean clearIgnore(String blockerUuid, String blockedUuid) throws SQLException {
        return transaction(tx -> tx.clearIgnore(blockerUuid, blockedUuid)) > 0;
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

    /** Active ignore entries joined with the blocked player's known name (null when unknown). */
    public List<IgnoreView> listIgnores(String blockerUuid, long now) throws SQLException {
        return queryList(("SELECT i.blocked_uuid, i.expires_at, i.created_at, p.name AS blocked_name "
                + "FROM %signores i LEFT JOIN %splayers p ON p.uuid = i.blocked_uuid "
                + "WHERE i.blocker_uuid = ? AND (i.expires_at = ? OR i.expires_at > ?)").formatted(prefix, prefix),
                ps -> {
                    ps.setString(1, blockerUuid);
                    ps.setLong(2, IgnoreEntry.PERMANENT);
                    ps.setLong(3, now);
                }, rs -> new IgnoreView(rs.getString("blocked_uuid"), rs.getString("blocked_name"),
                        rs.getLong("expires_at"), rs.getLong("created_at")));
    }

    public List<IgnoreEntry> listAllIgnores() throws SQLException {
        return queryList("SELECT * FROM %signores".formatted(prefix),
                rs -> new IgnoreEntry(rs.getString("blocker_uuid"), rs.getString("blocked_uuid"),
                        rs.getLong("expires_at"), rs.getLong("created_at")));
    }

    public record IgnoreView(String blockedUuid, String name, long expiresAt, long createdAt) {
    }

    // ------------------------------------------------------------------ plumbing

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
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