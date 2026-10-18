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
        h.setMaximumPoolSize(1);
        // Covers a batch transaction plus one queued reader: a longer wait than
        // the 5s default keeps a busy batch from failing an unrelated query.
        h.setConnectionTimeout(15000);
        h.setDriverClassName("org.sqlite.JDBC");
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
