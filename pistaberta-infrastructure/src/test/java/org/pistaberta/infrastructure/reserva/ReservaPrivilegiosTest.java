package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** RV8 · mínimo privilegio (D-008): reserva no se borra, se cancela; pista es de solo lectura para la app. */
@SpringBootTest
@ActiveProfiles("test")
class ReservaPrivilegiosTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    private static boolean puede(Connection c, String tabla, String privilegio) throws SQLException {
        return TestPostgres.count(c, "select case when has_table_privilege('pistaberta_app', 'public." + tabla + "', '"
                + privilegio + "') then 1 else 0 end") == 1;
    }

    @Test
    void reservaCanBeReadInsertedAndUpdatedButNotDeleted() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            assertThat(puede(c, "reserva", "SELECT")).isTrue();
            assertThat(puede(c, "reserva", "INSERT")).isTrue();
            assertThat(puede(c, "reserva", "UPDATE")).isTrue();
            assertThat(puede(c, "reserva", "DELETE")).as("reserva no se borra, se cancela").isFalse();
        }
    }

    @Test
    void pistaIsReadOnlyForTheApp() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            assertThat(puede(c, "pista", "SELECT")).isTrue();
            assertThat(puede(c, "pista", "INSERT")).isFalse();
            assertThat(puede(c, "pista", "UPDATE")).isFalse();
            assertThat(puede(c, "pista", "DELETE")).isFalse();
        }
    }
}
