package com.oAT.web.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class PostgresqlDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String JDBC_POSTGRESQL_PREFIX = "jdbc:postgresql:";
    private static final String CREATE_DATABASE_PROPERTY = "oat.datasource.postgresql.create-database-if-missing";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(CREATE_DATABASE_PROPERTY, Boolean.class, true)) {
            return;
        }

        String url = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(url) || !url.startsWith(JDBC_POSTGRESQL_PREFIX)) {
            return;
        }

        ParsedPostgresqlUrl parsedUrl = ParsedPostgresqlUrl.parse(url);
        if (parsedUrl == null || !StringUtils.hasText(parsedUrl.database())
                || "postgres".equalsIgnoreCase(parsedUrl.database())) {
            return;
        }

        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        ensureDatabaseExists(parsedUrl, username, password);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private void ensureDatabaseExists(ParsedPostgresqlUrl parsedUrl, String username, String password) {
        Properties properties = new Properties();
        if (StringUtils.hasText(username)) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }

        try (Connection connection = DriverManager.getConnection(parsedUrl.maintenanceUrl(), properties)) {
            if (databaseExists(connection, parsedUrl.database())) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + quoteIdentifier(parsedUrl.database()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create PostgreSQL database '" + parsedUrl.database()
                    + "'. Please create it manually or set " + CREATE_DATABASE_PROPERTY + "=false.", e);
        }
    }

    private boolean databaseExists(Connection connection, String database) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, database);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record ParsedPostgresqlUrl(String database, String maintenanceUrl) {
        private static ParsedPostgresqlUrl parse(String url) {
            String rest = url.substring(JDBC_POSTGRESQL_PREFIX.length());
            int queryStart = rest.indexOf('?');
            String base = queryStart >= 0 ? rest.substring(0, queryStart) : rest;
            String query = queryStart >= 0 ? rest.substring(queryStart + 1) : "";

            String database;
            String maintenanceBase;
            if (base.startsWith("//")) {
                int databaseStart = base.indexOf('/', 2);
                if (databaseStart < 0 || databaseStart == base.length() - 1) {
                    return null;
                }
                database = base.substring(databaseStart + 1);
                maintenanceBase = base.substring(0, databaseStart + 1) + "postgres";
            } else {
                if (!StringUtils.hasText(base)) {
                    return null;
                }
                database = base;
                maintenanceBase = "postgres";
            }

            String normalizedDatabase = database.trim();
            if (normalizedDatabase.contains("/")) {
                return null;
            }
            String maintenanceUrl = JDBC_POSTGRESQL_PREFIX + maintenanceBase + cleanedQuery(query);
            return new ParsedPostgresqlUrl(normalizedDatabase, maintenanceUrl);
        }

        private static String cleanedQuery(String query) {
            if (!StringUtils.hasText(query)) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (String part : query.split("&")) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                String key = part;
                int equals = part.indexOf('=');
                if (equals >= 0) {
                    key = part.substring(0, equals);
                }
                if ("currentSchema".equalsIgnoreCase(key)) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append('&');
                }
                builder.append(part);
            }
            return builder.isEmpty() ? "" : "?" + builder;
        }
    }
}
