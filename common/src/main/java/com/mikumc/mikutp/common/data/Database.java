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

    /** How long a deletion tombstone is kept. Well beyond any resync dump. */
    private static final long TOMBSTONE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Deliberate tombstone prune cadence: keeps the table bounded on long uptimes. */
    private static final int TOMBSTONE_PRUNE_EVERY = 1024;

    /** The only column prefixes {@link #setPosition} accepts (also whitelisted against SQL injection). */
    private static final Set<String> POSITION_PREFIXES = Set.of(BACK_PREFIX, DEATH_PREFIX, LOGOUT_PREFIX);

    /**
     * Version-stamp column of one stored position. The stamp is per position
     * kind, otherwise a death update would invalidate an older /back update.
     */
    private static String stampColumn(String colPrefix) {
        if (!POSITION_PREFIXES.contains(colPrefix)) {
            throw new IllegalArgumentException("Unsupported stored-position column prefix: " + colPrefix);
        }
        return colPrefix + "updated_at";
    }

    /** Supplies pooled connections; implemented by the platform modules. */
    public interface ConnectionProvider extends AutoCloseable {
        Connection acquire() throws SQLException;

        @Override
        void close() throws Exception;
    }

    /** Write operations bound to one connection, only valid inside a {@link #transaction}. */
    public interface Tx {
        int saveHome(Home home) throws SQLException;

        /** Deletes a home and records a tombstone so a later stale replication
         * cannot resurrect it. */
        int deleteHome(String ownerUuid, String name, long deletedAt) throws SQLException;

        int upsertPlayer(String uuid, String name, long now) throws SQLException;

        /** Stores the toggle and stamps the profile as of {@code at}, unless the
         * row already carries a newer profile change. */
        int setTpaEnabled(String uuid, boolean enabled, long at) throws SQLException;

        /** Stores a return position under {@code colPrefix}; a null position clears it. */
        int setPosition(String uuid, Position pos, String colPrefix) throws SQLException;

        /** Stores a return position only when {@code updatedAt} is at least the
         * stored version stamp; returns whether the row was written. */
        boolean setPositionIfNewer(String uuid, Position pos, String colPrefix, long updatedAt)
                throws SQLException;

        int addIgnore(IgnoreEntry entry) throws SQLException;

        /** Removes an ignore entry and records a tombstone (same reason as homes). */
        int clearIgnore(String blockerUuid, String blockedUuid, long deletedAt) throws SQLException;
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
    /** Guards against nested transactions: with a small pool a nested acquire can
     *  block until the connection timeout, which is indistinguishable from a hang. */
    private final ThreadLocal<Boolean> inTransaction = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Counts deletions since the last tombstone prune (keeps the table bounded). */
    private final java.util.concurrent.atomic.AtomicInteger deletionsSincePrune =
            new java.util.concurrent.atomic.AtomicInteger();

    public Database(ConnectionProvider provider, String prefix) {
        if (prefix != null && !TABLE_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid storage.table_prefix (letters, digits and '_' only): " + prefix);
        }
        this.provider = provider;
        this.prefix = prefix == null ? "" : prefix;
    }

    // ------------------------------------------------------------------ schema

    /**
     * Creates/upgrades the schema. Journal mode is a database property and is
     * switched to WAL once here; {@code synchronous} is connection-scoped and is
     * therefore set per connection by the platform pool (not here).
     */
    public void init() throws SQLException {
        enableWal();
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
        // The back-position columns predate the death/logout ones, but the very
        // oldest schema predates them too; add them when a legacy table is missing
        // them so /back and the replicated position sync keep working.
        expected.put("back_server", "VARCHAR(64)");
        expected.put("back_world", "VARCHAR(64)");
        expected.put("back_x", "DOUBLE");
        expected.put("back_y", "DOUBLE");
        expected.put("back_z", "DOUBLE");
        expected.put("back_yaw", "DOUBLE");
        expected.put("back_pitch", "DOUBLE");
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
        // Version stamps of the stored positions (M-02): per kind, so updates of
        // one kind cannot invalidate a newer update of the other.
        expected.put("back_updated_at", "INTEGER NOT NULL DEFAULT 0");
        expected.put("death_updated_at", "INTEGER NOT NULL DEFAULT 0");
        expected.put("logout_updated_at", "INTEGER NOT NULL DEFAULT 0");
        // Set once an authoritative tpa toggle has been stored (M-03); a plain
        // profile upsert must never claim the toggle is known.
        expected.put("tpa_known", "INTEGER NOT NULL DEFAULT 0");
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
        // Deletion tombstones. Replication is last-writer-wins over full state
        // dumps, so a backend that missed a delete would otherwise re-publish
        // the deleted row and resurrect it everywhere. A tombstone outlives the
        // row and blocks any insert older than the delete.
        exec("""
                CREATE TABLE IF NOT EXISTS %stombstones (
                  kind VARCHAR(16) NOT NULL,
                  owner VARCHAR(36) NOT NULL,
                  name VARCHAR(64) NOT NULL,
                  deleted_at BIGINT NOT NULL,
                  PRIMARY KEY (kind, owner, name)
                )""".formatted(prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_homes_owner ON %shomes (owner_uuid)".formatted(prefix, prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_players_name ON %splayers (name COLLATE NOCASE)".formatted(prefix, prefix));
        index("CREATE INDEX IF NOT EXISTS %sidx_warps_server ON %swarps (server)".formatted(prefix, prefix));
        // Tombstones only need to outlive the replication window (a resync dump).
        // Anything older cannot be contradicted by a snapshot that still exists.
        exec("DELETE FROM %stombstones WHERE deleted_at < %d"
                .formatted(prefix, System.currentTimeMillis() - TOMBSTONE_TTL_MS));
    }

    private void index(String sql) throws SQLException {
        try {
            exec(sql);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("already exists")) {
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

    /**
     * Switches the database file to WAL and verifies it. WAL is required for the
     * pool to serve concurrent readers while a writer holds the write lock;
     * in-memory databases (unit tests) legitimately report "memory".
     */
    private void enableWal() throws SQLException {
        String mode;
        try (Connection c = provider.acquire();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
            mode = rs.next() ? rs.getString(1) : null;
        }
        if (mode == null || !(mode.equalsIgnoreCase("wal") || mode.equalsIgnoreCase("memory"))) {
            throw new SQLException("Could not enable SQLite WAL journal mode (got: " + mode
                    + "). The plugin needs a writable, file-backed database.");
        }
    }

    /** Deletes tombstones that are no longer needed to reject stale snapshots. */
    private void pruneTombstones(Connection connection, long cutoff) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM %stombstones WHERE deleted_at < ?".formatted(prefix))) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ transactions

    /** Runs {@code work} on one connection inside a single commit. */
    public <T> T transaction(TxWork<T> work) throws SQLException {
        if (Boolean.TRUE.equals(inTransaction.get())) {
            throw new IllegalStateException(
                    "nested Database.transaction(): use the Tx handle inside the running transaction");
        }
        inTransaction.set(Boolean.TRUE);
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
                try {
                    c.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // Hikari restores autoCommit when the connection returns to the
                    // pool; a failure here is secondary and must not mask the real cause.
                }
            }
        } finally {
            inTransaction.remove();
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
            if (deletedAfter("home", home.ownerUuid, home.name, home.createdAt)) {
                // A delete for this home happened after this snapshot was made:
                // re-inserting it would resurrect it on every backend.
                return 0;
            }
            // Home names are identifiers. Rows written by older versions may still
            // carry mixed case, and the primary key is case-sensitive, so a
            // replicated "base" next to a legacy "Base" would create a duplicate.
            // Drop the case variants first, keeping the newer row.
            try (PreparedStatement ps = connection.prepareStatement(
                    ("DELETE FROM %shomes WHERE owner_uuid = ? AND name = ? COLLATE NOCASE AND name <> ?"
                            + " AND created_at <= ?").formatted(prefix))) {
                ps.setString(1, home.ownerUuid);
                ps.setString(2, home.name);
                ps.setString(3, home.name);
                // Only drop a case variant that is not newer than this snapshot: a
                // stale replicated "base" must never delete the newer legacy "Base".
                ps.setLong(4, home.createdAt);
                ps.executeUpdate();
            }
            // A newer case variant still owns this name: writing the older snapshot
            // would create a divergent duplicate (the primary key is case-sensitive),
            // so the stale snapshot is dropped instead of being inserted.
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM %shomes WHERE owner_uuid = ? AND name = ? COLLATE NOCASE AND name <> ? LIMIT 1"
                            .formatted(prefix))) {
                ps.setString(1, home.ownerUuid);
                ps.setString(2, home.name);
                ps.setString(3, home.name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return 0;
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO %shomes (owner_uuid, name, server, world, x, y, z, yaw, pitch, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(owner_uuid, name) DO UPDATE SET server = excluded.server, world = excluded.world,
                      x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch,
                      created_at = excluded.created_at
                    WHERE excluded.created_at >= %shomes.created_at
                    """.formatted(prefix, prefix))) {
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
        public int deleteHome(String ownerUuid, String name, long deletedAt) throws SQLException {
            int removed;
            // Only rows older than the deletion go: a home re-created after the
            // delete (a fresh /sethome with the same name) must survive a stale
            // replicated delete.
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM %shomes WHERE owner_uuid = ? AND name = ? AND created_at < ?".formatted(prefix))) {
                ps.setString(1, ownerUuid);
                ps.setString(2, name);
                ps.setLong(3, deletedAt);
                removed = ps.executeUpdate();
            }
            recordDeletion("home", ownerUuid, name, deletedAt);
            return removed;
        }

        @Override
        public int upsertPlayer(String uuid, String name, long now) throws SQLException {
            // Last-writer-wins by profile timestamp: a replicated snapshot that
            // was produced earlier must not roll back a newer local change
            // (name, last-online order, tpa toggle). No in-memory version map is
            // needed, so nothing has to be cleaned up per player.
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO %splayers (uuid, name, last_online, tpa_enabled) VALUES (?, ?, ?, 1)
                    ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_online = excluded.last_online
                    WHERE excluded.last_online >= %splayers.last_online
                    """.formatted(prefix, prefix))) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setLong(3, now);
                return ps.executeUpdate();
            }
        }

        @Override
        public int setTpaEnabled(String uuid, boolean enabled, long at) throws SQLException {
            // Bumping last_online stamps the toggle as the newest profile change,
            // so any snapshot produced before it is rejected by the upsert guard.
            // The same guard here keeps a late snapshot from undoing a newer flip.
            try (PreparedStatement ps = connection.prepareStatement(
                    ("UPDATE %splayers SET tpa_enabled = ?, tpa_known = 1, last_online = ?"
                            + " WHERE uuid = ? AND last_online <= ?").formatted(prefix))) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setLong(2, at);
                ps.setString(3, uuid);
                ps.setLong(4, at);
                return ps.executeUpdate();
            }
        }

        @Override
        public int setPosition(String uuid, Position pos, String colPrefix) throws SQLException {
            return writePosition(uuid, pos, colPrefix, System.currentTimeMillis(), false);
        }

        @Override
        public boolean setPositionIfNewer(String uuid, Position pos, String colPrefix, long updatedAt)
                throws SQLException {
            return writePosition(uuid, pos, colPrefix, updatedAt, true) > 0;
        }

        /**
         * One upsert for a stored position plus its version stamp. A replicated
         * position may arrive before the player's profile row exists, hence the
         * placeholder row: name = '' and last_online = 0 never look like a newer
         * profile to the profile upsert guard.
         *
         * <p>{@code newerWins} adds the last-writer-wins guard so a stale snapshot
         * cannot roll a newer /back or /dback position back.
         */
        private int writePosition(String uuid, Position pos, String colPrefix, long updatedAt, boolean newerWins)
                throws SQLException {
            String stamp = stampColumn(colPrefix);
            String sql = ("INSERT INTO %splayers (uuid, name, last_online, tpa_enabled, "
                    + colPrefix + "server, " + colPrefix + "world, " + colPrefix + "x, "
                    + colPrefix + "y, " + colPrefix + "z, " + colPrefix + "yaw, " + colPrefix + "pitch, "
                    + stamp + ") "
                    + "VALUES (?, '', 0, 1, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET "
                    + colPrefix + "server = excluded." + colPrefix + "server, "
                    + colPrefix + "world = excluded." + colPrefix + "world, "
                    + colPrefix + "x = excluded." + colPrefix + "x, "
                    + colPrefix + "y = excluded." + colPrefix + "y, "
                    + colPrefix + "z = excluded." + colPrefix + "z, "
                    + colPrefix + "yaw = excluded." + colPrefix + "yaw, "
                    + colPrefix + "pitch = excluded." + colPrefix + "pitch, "
                    + stamp + " = excluded." + stamp).formatted(prefix);
            if (newerWins) {
                sql = sql + " WHERE excluded." + stamp + " >= " + prefix + "players." + stamp;
            }
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid);
                if (pos == null) {
                    ps.setString(2, null);
                    ps.setString(3, null);
                    ps.setDouble(4, 0);
                    ps.setDouble(5, 0);
                    ps.setDouble(6, 0);
                    ps.setDouble(7, 0);
                    ps.setDouble(8, 0);
                } else {
                    ps.setString(2, pos.server);
                    ps.setString(3, pos.world);
                    ps.setDouble(4, pos.x);
                    ps.setDouble(5, pos.y);
                    ps.setDouble(6, pos.z);
                    ps.setDouble(7, pos.yaw);
                    ps.setDouble(8, pos.pitch);
                }
                ps.setLong(9, updatedAt);
                return ps.executeUpdate();
            }
        }

        @Override
        public int addIgnore(IgnoreEntry e) throws SQLException {
            if (deletedAfter("ignore", e.blockerUuid, e.blockedUuid, e.createdAt)) {
                // The block was lifted after this entry was created: honour the
                // newer delete instead of re-creating the entry.
                return 0;
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO %signores (blocker_uuid, blocked_uuid, expires_at, created_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(blocker_uuid, blocked_uuid) DO UPDATE SET expires_at = excluded.expires_at,
                      created_at = excluded.created_at
                    WHERE excluded.created_at >= %signores.created_at
                    """.formatted(prefix, prefix))) {
                ps.setString(1, e.blockerUuid);
                ps.setString(2, e.blockedUuid);
                ps.setLong(3, e.expiresAt);
                ps.setLong(4, e.createdAt);
                return ps.executeUpdate();
            }
        }

        @Override
        public int clearIgnore(String blockerUuid, String blockedUuid, long deletedAt) throws SQLException {
            int removed;
            // Only the row that existed when the delete was made goes: a block
            // re-created afterwards must survive a replayed tombstone from a dump.
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM %signores WHERE blocker_uuid = ? AND blocked_uuid = ? AND created_at < ?"
                            .formatted(prefix))) {
                ps.setString(1, blockerUuid);
                ps.setString(2, blockedUuid);
                ps.setLong(3, deletedAt);
                removed = ps.executeUpdate();
            }
            recordDeletion("ignore", blockerUuid, blockedUuid, deletedAt);
            return removed;
        }

        /** True when a delete for this row is newer than the snapshot being written. */
        private boolean deletedAfter(String kind, String owner, String name, long snapshotAt) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT deleted_at FROM %stombstones WHERE kind = ? AND owner = ? AND name = ?".formatted(prefix))) {
                ps.setString(1, kind);
                ps.setString(2, owner);
                ps.setString(3, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getLong(1) > snapshotAt;
                }
            }
        }

        /** Upserts a deletion stamp, keeping the newest one. */
        private void recordDeletion(String kind, String owner, String name, long deletedAt) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO %stombstones (kind, owner, name, deleted_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(kind, owner, name) DO UPDATE SET deleted_at = excluded.deleted_at
                    WHERE excluded.deleted_at > %stombstones.deleted_at
                    """.formatted(prefix, prefix))) {
                ps.setString(1, kind);
                ps.setString(2, owner);
                ps.setString(3, name);
                ps.setLong(4, deletedAt);
                ps.executeUpdate();
            }
            if (deletionsSincePrune.incrementAndGet() >= TOMBSTONE_PRUNE_EVERY) {
                deletionsSincePrune.set(0);
                pruneTombstones(connection, System.currentTimeMillis() - TOMBSTONE_TTL_MS);
            }
        }
    }

    // ------------------------------------------------------------------ players

    public void upsertPlayer(String uuid, String name, long now) throws SQLException {
        runTransaction(tx -> tx.upsertPlayer(uuid, name, now));
    }

    public Optional<PlayerProfile> getPlayer(String uuid) throws SQLException {
        return queryOne(
                "SELECT uuid, name, last_online, tpa_enabled, tpa_known FROM %splayers WHERE uuid = ?"
                        .formatted(prefix),
                ps -> ps.setString(1, uuid), Database::readProfile);
    }

    public Optional<PlayerProfile> getPlayerByName(String name) throws SQLException {
        return queryOne(("SELECT uuid, name, last_online, tpa_enabled, tpa_known FROM %splayers"
                + " WHERE name = ? COLLATE NOCASE ORDER BY last_online DESC LIMIT 1").formatted(prefix),
                ps -> ps.setString(1, name), Database::readProfile);
    }

    public void setTpaEnabled(String uuid, boolean enabled) throws SQLException {
        long now = System.currentTimeMillis();
        runTransaction(tx -> tx.setTpaEnabled(uuid, enabled, now));
    }

    private static PlayerProfile readProfile(ResultSet rs) throws SQLException {
        return new PlayerProfile(rs.getString("uuid"), rs.getString("name"),
                rs.getLong("last_online"), rs.getInt("tpa_enabled") != 0, rs.getInt("tpa_known") != 0);
    }

    /**
     * Every known profile on this server, for resync dumps. Rows created as a
     * position placeholder (name = '', last_online = 0) are skipped: they carry no
     * identity and must not be republished as if they were a player profile.
     */
    public List<PlayerProfile> listAllProfiles() throws SQLException {
        return queryList(
                ("SELECT uuid, name, last_online, tpa_enabled, tpa_known FROM %splayers"
                        + " WHERE name <> ''").formatted(prefix),
                Database::readProfile);
    }

    // ------------------------------------------------------------------ stored positions (back / death / logout)

    /** Stores a return position under the given column prefix; {@code null} clears it. */
    public void setPosition(String uuid, Position pos, String colPrefix) throws SQLException {
        // Validate before touching the database so a bad prefix fails loudly.
        stampColumn(colPrefix);
        runTransaction(tx -> tx.setPosition(uuid, pos, colPrefix));
    }

    /**
     * Applies a replicated position only when {@code updatedAt} is at least the
     * stored version stamp, so a stale snapshot cannot roll a newer position back.
     *
     * @return true when the row was written
     */
    public boolean setPositionIfNewer(String uuid, Position pos, String colPrefix, long updatedAt)
            throws SQLException {
        stampColumn(colPrefix);
        return transaction(tx -> tx.setPositionIfNewer(uuid, pos, colPrefix, updatedAt));
    }

    /** Version stamp of one stored position; 0 when the row (or stamp) is absent. */
    public long getPositionUpdatedAt(String uuid, String colPrefix) throws SQLException {
        String stamp = stampColumn(colPrefix);
        return queryOne("SELECT " + stamp + " FROM %splayers WHERE uuid = ?".formatted(prefix),
                ps -> ps.setString(1, uuid), rs -> rs.getLong(1)).orElse(0L);
    }

    public Optional<Position> getPosition(String uuid, String colPrefix) throws SQLException {
        stampColumn(colPrefix);
        return queryOne(("SELECT " + colPrefix + "server, " + colPrefix + "world, " + colPrefix + "x, "
                + colPrefix + "y, " + colPrefix + "z, " + colPrefix + "yaw, " + colPrefix + "pitch"
                + " FROM %splayers WHERE uuid = ?").formatted(prefix),
                ps -> ps.setString(1, uuid), rs -> readPrefixedPosition(rs, colPrefix));
    }

    private static Position readPrefixedPosition(ResultSet rs, String colPrefix) throws SQLException {
        String server = rs.getString(colPrefix + "server");
        String world = rs.getString(colPrefix + "world");
        if (server == null || server.isBlank() || world == null) {
            // Same normalisation as readPosition(): a blank server means "unknown",
            // which is "no stored position" for the caller.
            return null;
        }
        return new Position(server, world, rs.getDouble(colPrefix + "x"), rs.getDouble(colPrefix + "y"),
                rs.getDouble(colPrefix + "z"), (float) rs.getDouble(colPrefix + "yaw"),
                (float) rs.getDouble(colPrefix + "pitch"));
    }

    /** Every stored return position on this server, for resync dumps. */
    public List<StoredPositions> listAllStoredPositions() throws SQLException {
        return queryList(("SELECT uuid, back_server, back_world, back_x, back_y, back_z, back_yaw, back_pitch, "
                + "death_server, death_world, death_x, death_y, death_z, death_yaw, death_pitch, "
                + "back_updated_at, death_updated_at "
                + "FROM %splayers WHERE back_server IS NOT NULL OR death_server IS NOT NULL").formatted(prefix),
                rs -> new StoredPositions(rs.getString("uuid"),
                        readPrefixedPosition(rs, BACK_PREFIX), readPrefixedPosition(rs, DEATH_PREFIX),
                        rs.getLong("back_updated_at"), rs.getLong("death_updated_at")));
    }

    /** Stored positions of one player plus the version stamp of each kind. */
    public record StoredPositions(String uuid, Position back, Position death,
                                  long backUpdatedAt, long deathUpdatedAt) {
    }

    // ------------------------------------------------------------------ homes

    /**
     * Saves a home.
     *
     * @return true when the row was written; false when a newer deletion tombstone
     *         or a newer stored row rejected the snapshot (the caller must then not
     *         treat the home as saved).
     */
    public boolean saveHome(Home home) throws SQLException {
        return transaction(tx -> tx.saveHome(home)) > 0;
    }

    public boolean deleteHome(String ownerUuid, String name) throws SQLException {
        long now = System.currentTimeMillis();
        return transaction(tx -> tx.deleteHome(ownerUuid, name, now)) > 0;
    }

    public Optional<Home> getHome(String ownerUuid, String name) throws SQLException {
        // Case-insensitive: home names are normalised to lowercase, but rows
        // written by older versions may still carry mixed case.
        return queryOne(("SELECT * FROM %shomes WHERE owner_uuid = ? AND name = ? COLLATE NOCASE"
                + " ORDER BY created_at DESC LIMIT 1").formatted(prefix),
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

    /** A recorded deletion, replicated so a backend that missed the event
     * converges instead of resurrecting the row. */
    public record Tombstone(String kind, String owner, String name, long deletedAt) {
    }

    public List<Tombstone> listTombstones() throws SQLException {
        long cutoff = System.currentTimeMillis() - TOMBSTONE_TTL_MS;
        return queryList(("SELECT kind, owner, name, deleted_at FROM %stombstones"
                + " WHERE deleted_at >= ?").formatted(prefix),
                ps -> ps.setLong(1, cutoff),
                rs -> new Tombstone(rs.getString("kind"), rs.getString("owner"),
                        rs.getString("name"), rs.getLong("deleted_at")));
    }

    private static Position readPosition(ResultSet rs) throws SQLException {
        String server = rs.getString("server");
        return new Position(server == null || server.isBlank() ? null : server,
                rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                (float) rs.getDouble("yaw"), (float) rs.getDouble("pitch"));
    }

    // ------------------------------------------------------------------ warps

    public void saveWarp(Warp warp) throws SQLException {
        assertNotInTransaction("saveWarp");
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
        assertNotInTransaction("deleteWarp");
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
        long now = System.currentTimeMillis();
        return transaction(tx -> tx.clearIgnore(blockerUuid, blockedUuid, now)) > 0;
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

    /**
     * Rejects a pooled acquire from inside an open transaction: with a small pool
     * that would block until the connection timeout instead of failing fast.
     */
    private void assertNotInTransaction(String operation) {
        if (Boolean.TRUE.equals(inTransaction.get())) {
            throw new IllegalStateException(operation
                    + " called from inside Database.transaction(); use the Tx handle instead");
        }
    }

    private <T> Optional<T> queryOne(String sql, Binder binder, RowMapper<T> mapper) throws SQLException {
        assertNotInTransaction("query");
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
        assertNotInTransaction("query");
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
        assertNotInTransaction("query");
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
        assertNotInTransaction("query");
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