package org.pistaberta.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** F3: un rol pre-aprovisionado con BYPASSRLS haría pasar V2 con RLS desactivada en silencio: V2 debe fallar. */
@Testcontainers
class V2RoleSafetyTest {

    @Container
    static final PostgreSQLContainer<?> DB = TestPostgres.newContainer();

    @Test
    void migrationRefusesAPreProvisionedRoleThatBypassesRls() throws Exception {
        try (Connection c = TestPostgres.superuser(DB); Statement st = c.createStatement()) {
            st.execute("CREATE ROLE pistaberta_app LOGIN BYPASSRLS PASSWORD 'ops'");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_db_password", "unused"))
                .load();

        assertThatThrownBy(flyway::migrate).hasMessageContaining("BYPASSRLS");
    }
}
