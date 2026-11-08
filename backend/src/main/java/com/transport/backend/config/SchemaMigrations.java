package com.transport.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * Small, idempotent schema changes that Hibernate's ddl-auto=update cannot make by itself.
 * Each step checks the current schema first and only ever widens it (no data is removed).
 * Runs before DataInitializer.
 *
 * 1. crowd_predictions.crowd_level: Hibernate created a MySQL ENUM('LOW','MEDIUM','HIGH');
 *    the live occupancy forecast adds the level FULL, so the ENUM is extended.
 * 2. routes.path_geometry: widened to MEDIUMTEXT (road geometry of a route is larger than TINYTEXT).
 */
@Component
@Order(0)
public class SchemaMigrations implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public SchemaMigrations(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")) {
                return; // H2 (tests) creates the schema from scratch
            }
        }
        List<String> types = jdbc.queryForList("""
                select column_type from information_schema.columns
                where table_schema = database() and table_name = 'crowd_predictions' and column_name = 'crowd_level'
                """, String.class);
        if (!types.isEmpty() && types.get(0).startsWith("enum(") && !types.get(0).contains("'FULL'")) {
            jdbc.execute("alter table crowd_predictions modify crowd_level enum('LOW','MEDIUM','HIGH','FULL') not null");
            log.info("Schema migration: added FULL to crowd_predictions.crowd_level");
        }
        // 2. routes.path_geometry holds road geometry (tens of kB); an older build created it as TINYTEXT (255 bytes)
        List<String> geometry = jdbc.queryForList("""
                select data_type from information_schema.columns
                where table_schema = database() and table_name = 'routes' and column_name = 'path_geometry'
                """, String.class);
        if (!geometry.isEmpty() && !List.of("mediumtext", "longtext").contains(geometry.get(0).toLowerCase())) {
            jdbc.execute("alter table routes modify path_geometry mediumtext null");
            log.info("Schema migration: widened routes.path_geometry from {} to MEDIUMTEXT", geometry.get(0));
        }
    }
}
