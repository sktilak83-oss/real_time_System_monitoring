package com.neurosys.backend.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;

@Slf4j
@Configuration
public class DatabaseConfig {

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${spring.datasource.url:}")
    private String springDatasourceUrl;

    @Value("${spring.datasource.username:root}")
    private String springDatasourceUsername;

    @Value("${spring.datasource.password:}")
    private String springDatasourcePassword;

    @Bean
    @Primary
    public DataSource dataSource() {
        String rawUrl = springDatasourceUrl != null && !springDatasourceUrl.trim().isEmpty() ? springDatasourceUrl : System.getenv("SPRING_DATASOURCE_URL");
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            rawUrl = System.getenv("MYSQL_URL");
        }
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            rawUrl = System.getenv("MYSQL_PRIVATE_URL");
        }
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            rawUrl = System.getenv("MYSQL_PUBLIC_URL");
        }
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            rawUrl = System.getenv("MYSQLPRIVATEURL");
        }
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            rawUrl = System.getenv("DATABASE_URL");
        }

        String user = springDatasourceUsername != null && !springDatasourceUsername.trim().isEmpty() ? springDatasourceUsername : System.getenv("MYSQLUSER");
        if (user == null || user.trim().isEmpty()) {
            user = System.getenv("MYSQL_USER");
        }
        String pass = springDatasourcePassword != null && !springDatasourcePassword.trim().isEmpty() ? springDatasourcePassword : System.getenv("MYSQLPASSWORD");
        if (pass == null || pass.trim().isEmpty()) {
            pass = System.getenv("MYSQL_PASSWORD");
        }

        log.info("[DATABASE CONFIG] Profile: {}, URL: {}, User: {}, HasPass: {}",
                activeProfile, rawUrl, user, (pass != null && !pass.isEmpty()));

        HikariConfig config = new HikariConfig();
        boolean configured = false;

        // 1. Direct JDBC URL
        if (rawUrl != null && rawUrl.startsWith("jdbc:mysql://")) {
            log.info("[DATABASE CONFIG] Configuring Hikari DataSource with direct JDBC URL scheme");
            config.setJdbcUrl(rawUrl);
            config.setUsername(user != null ? user : "root");
            config.setPassword(pass != null ? pass : "");
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            configured = true;
        }

        // 2. Parse Raw mysql:// URI from Railway
        if (!configured && rawUrl != null && rawUrl.startsWith("mysql://")) {
            try {
                URI uri = new URI(rawUrl);
                String userInfo = uri.getUserInfo();
                String username = user != null ? user : "root";
                String password = pass != null ? pass : "";
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    username = parts[0];
                    password = parts[1];
                }
                String hostName = uri.getHost();
                int portNum = uri.getPort() > 0 ? uri.getPort() : 3306;
                String dbName = uri.getPath() != null && uri.getPath().length() > 1 ? uri.getPath().substring(1) : "railway";

                String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        hostName, portNum, dbName);
                log.info("[DATABASE CONFIG] Configured Railway MySQL DataSource (Host: {}, Port: {}, DB: {})", hostName, portNum, dbName);
                config.setJdbcUrl(jdbcUrl);
                config.setUsername(username);
                config.setPassword(password);
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                configured = true;
            } catch (Exception e) {
                log.error("Failed to parse raw MYSQL_URL: {}, falling back", rawUrl, e);
            }
        }

        // 3. Fallback using MYSQLHOST, MYSQLPORT, MYSQLDATABASE
        if (!configured) {
            String host = System.getenv("MYSQLHOST");
            if (host == null || host.isBlank()) {
                host = System.getenv("MYSQL_HOST");
            }
            if (host == null || host.isBlank()) {
                host = "prod".equalsIgnoreCase(activeProfile) ? "mysql.railway.internal" : "localhost";
            }

            String port = System.getenv("MYSQLPORT");
            if (port == null || port.isBlank()) {
                port = System.getenv("MYSQL_PORT");
            }
            if (port == null || port.isBlank()) {
                port = "3306";
            }

            String db = System.getenv("MYSQLDATABASE");
            if (db == null || db.isBlank()) {
                db = System.getenv("MYSQL_DATABASE");
            }
            if (db == null || db.isBlank()) {
                db = "railway";
            }

            String defaultPass = "prod".equalsIgnoreCase(activeProfile) ? "" : "Karthik@2005";
            String password = pass != null ? pass : defaultPass;

            String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", host, port, db);
            log.info("[DATABASE CONFIG] Configured Host/Port DataSource (Host: {}, Port: {}, DB: {})", host, port, db);
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(user != null ? user : "root");
            config.setPassword(password);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }

        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(10000);
        config.setMinimumIdle(5);
        config.setMaximumPoolSize(15);
        config.setKeepaliveTime(30000); // 30s ping keeps remote cloud connection warm & prevents drops
        config.setIdleTimeout(300000);  // 5m idle timeout
        config.setMaxLifetime(600000);  // 10m max connection lifetime

        // High-performance MySQL Driver properties to minimize cross-network round trips
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        return new HikariDataSource(config);
    }
}
