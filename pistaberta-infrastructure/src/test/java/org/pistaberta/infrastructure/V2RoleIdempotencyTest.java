package org.pistaberta.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V2 es idempotente con el rol: si ops ya lo pre-aprovisionó, no rompe ni le cambia la contraseña (D-006). */
@Testcontainers
class V2RoleIdempotencyTest {

    @Container
    static final PostgreSQLContainer<?> DB = TestPostgres.newContainer();

    @Test
    void migrationKeepsAPreProvisionedRole() throws Exception {
        try (Connection c = TestPostgres.superuser(DB); Statement st = c.createStatement()) {
            st.execute("CREATE ROLE pistaberta_app LOGIN PASSWORD 'preprovisioned'");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_db_password", "from-migration"))
                .load();

        assertThat(flyway.migrate().success).isTrue();
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), "pistaberta_app", "preprovisioned");
                Statement st = c.createStatement()) {
            assertThat(st.executeQuery("select count(*) from tenant").next()).as("grants aplicados").isTrue();
        }
    }
}
