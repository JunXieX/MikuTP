package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Opens the local SQLite database through a single pooled connection. */
public final class StorageFactory {

    private StorageFactory() {
    }

    public static Database create(Path dataFolder, MikuTPConfig config) {
        HikariConfig h = new HikariConfig();
        h.setPoolName("MikuTP-SQLite");
        // WAL mode allows one writer with concurrent readers (sqlite.org/wal.html:
        // "Readers and writers can proceed concurrently"). A single pooled
        // connection serialised every read behind the sync batch transaction, so
        // a /home or /back lookup could wait for a 2048-statement commit; four
        // connections keep read latency independent of write batch size while
        // SQLite itself still serialises writers.
        h.setMaximumPoolSize(4);
        // Covers a batch transaction plus a few queued operations: a longer wait
        // than the 5s default keeps a busy batch from failing an unrelated query.
        h.setConnectionTimeout(15000);
        h.setDriverClassName("org.sqlite.JDBC");
        // synchronous is per-connection; journal_mode=WAL is set once (database
        // file level) by Database.init(). NORMAL is the documented safe pairing
        // with WAL (a committed transaction can lose only the last commit on a
        // power failure, never corrupt the database).
        h.setConnectionInitSql("PRAGMA synchronous=NORMAL");
        // busy_timeout: WAL still returns SQLITE_BUSY in a few edge cases (e.g.
        // checkpointing); without a wait the write fails instead of retrying.
        h.setJdbcUrl("jdbc:sqlite:" + dataFolder.resolve("data.db").toAbsolutePath()
                + "?busy_timeout=5000");
        HikariDataSource dataSource = new HikariDataSource(h);
        return new Database(new PooledProvider(dataSource), config.storage.tablePrefix);
    }

    private record PooledProvider(HikariDataSource dataSource) implements Database.ConnectionProvider {

        @Override
        public Connection acquire() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }
}
