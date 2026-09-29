package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Builds the JDBC layer from config: a SQLite file or a MySQL pool. */
public final class StorageFactory {

    private StorageFactory() {
    }

    public static Database create(Path dataFolder, MikuTPConfig config) {
        String type = config.storage.type == null ? "SQLITE" : config.storage.type.trim().toUpperCase();
        HikariDataSource dataSource;
        Database.Dialect dialect;
        if (type.equals("MYSQL")) {
            var mysql = config.storage.mysql;
            HikariConfig h = new HikariConfig();
            h.setPoolName("MikuTP-MySQL");
            h.setMaximumPoolSize(Math.max(2, config.storage.poolSize));
            h.setDriverClassName("com.mysql.cj.jdbc.Driver");
            h.setJdbcUrl("jdbc:mysql://%s:%d/%s?useSSL=%s&characterEncoding=utf8&serverTimezone=UTC"
                    .formatted(mysql.host, mysql.port, mysql.database, mysql.useSsl));
            h.setUsername(mysql.user);
            h.setPassword(mysql.password);
            dataSource = new HikariDataSource(h);
            dialect = Database.Dialect.MYSQL;
        } else {
            HikariConfig h = new HikariConfig();
            h.setPoolName("MikuTP-SQLite");
            h.setMaximumPoolSize(1);
            h.setDriverClassName("org.sqlite.JDBC");
            h.setJdbcUrl("jdbc:sqlite:" + dataFolder.resolve("data.db").toAbsolutePath());
            dataSource = new HikariDataSource(h);
            dialect = Database.Dialect.SQLITE;
        }
        return new Database(new PooledProvider(dataSource), dialect, config.storage.tablePrefix);
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
