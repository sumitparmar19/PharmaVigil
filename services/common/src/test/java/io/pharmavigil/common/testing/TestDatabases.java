// Shared Testcontainers databases for repository and integration tests. One container per test JVM
// (started on first use) keeps suites fast. The engine comes from -Dpv.db: oracle (default) or mssql,
// so the same tests prove the JPA layer on Oracle and on SQL Server (Maven profile -Pmssql).
package io.pharmavigil.common.testing;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.oracle.OracleContainer;

public final class TestDatabases {

    public static final String ORACLE_IMAGE = "gvenzl/oracle-free:23-slim-faststart";
    public static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";

    private static JdbcDatabaseContainer<?> container;

    private TestDatabases() {
    }

    public static String engine() {
        return System.getProperty("pv.db", "oracle");
    }

    public static synchronized JdbcDatabaseContainer<?> container() {
        if (container == null) {
            container = switch (engine()) {
                // Oracle Free needs about 70 s on a small CI runner, more than the default wait.
                case "mssql" -> new MSSQLServerContainer(MSSQL_IMAGE).acceptLicense()
                        .withStartupTimeout(Duration.ofMinutes(5));
                case "oracle" -> new OracleContainer(ORACLE_IMAGE).withStartupTimeout(Duration.ofMinutes(5));
                default -> throw new IllegalArgumentException("pv.db must be oracle or mssql");
            };
            container.start();
        }
        return container;
    }

    /** Point the Spring datasource at the shared container. */
    public static void register(DynamicPropertyRegistry registry) {
        JdbcDatabaseContainer<?> c = container();
        registry.add("spring.datasource.url", c::getJdbcUrl);
        registry.add("spring.datasource.username", c::getUsername);
        registry.add("spring.datasource.password", c::getPassword);
    }
}
