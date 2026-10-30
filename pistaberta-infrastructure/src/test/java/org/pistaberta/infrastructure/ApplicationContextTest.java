package org.pistaberta.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Falla si el contexto no carga o si Flyway no aplica V1 y V2 sobre una BD PostGIS real.
 * Solo se inyectan credenciales del contenedor; la config de Flyway es la de application*.yml.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedV1() throws Exception {
        assertThat(successfulMigration("1")).isEqualTo("1");
    }

    @Test
    void flywayAppliedV2() throws Exception {
        assertThat(successfulMigration("2")).isEqualTo("2");
    }

    /** El rol de la app no lee flyway_schema_history (sin grants): se consulta con el migrador. */
    private static String successfulMigration(String version) throws Exception {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success AND version = '" + version + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void versionTableIsSeeded() throws Exception {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT version FROM app_version WHERE component = 'schema'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("0.1.0");
        }
    }

    @Test
    void appRoleIsNotPrivileged() {
        Boolean privileged = jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(TestPostgres.APP_ROLE);
        assertThat(privileged).isFalse();
    }
}
