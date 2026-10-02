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
        h.setConnectionTimeout(5000);
        h.setDriverClassName("org.sqlite.JDBC");
        h.setJdbcUrl("jdbc:sqlite:" + dataFolder.resolve("data.db").toAbsolutePath());
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
