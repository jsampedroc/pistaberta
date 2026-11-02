package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
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
            assertThat(puede(c, "reserva", "DELETE")).as("reserva no se borra, se cancela").isFalse();
        }
    }

    private static boolean puedeActualizar(Connection c, String columna) throws SQLException {
        return TestPostgres.count(c, "select case when has_column_privilege('pistaberta_app', 'public.reserva', '"
                + columna + "', 'UPDATE') then 1 else 0 end") == 1;
    }

    /** RV8 (F1): la app solo puede cambiar `estado`; la franxa, la pista y el tenant son inmutables para ella. */
    @Test
    void appCanOnlyUpdateTheEstadoColumn() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            assertThat(puedeActualizar(c, "estado")).as("cancelar = cambiar estado").isTrue();
            for (String columna : new String[] {"inicio", "fin", "pista_id", "tenant_id", "id"}) {
                assertThat(puedeActualizar(c, columna)).as("UPDATE sobre reserva.%s", columna).isFalse();
            }
        }
    }

    /** RV8 (F1) comportamental: el UPDATE de la franxa con el rol de la app se deniega (42501). */
    @Test
    void appCannotMoveAReservationFranxa() throws SQLException {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        UUID pista = UUID.randomUUID();
        TestPostgres.insertPista(TestPostgres.SHARED, TestPostgres.A_ESTRADA, pista, "Pista");
        try (Connection su = TestPostgres.superuser(TestPostgres.SHARED)) {
            TestPostgres.insertReserva(su, TestPostgres.A_ESTRADA, pista, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
        }
        try (Connection c = TestPostgres.appRole(TestPostgres.SHARED)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, TestPostgres.A_ESTRADA.toString());
                ps.execute();
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("update reserva set inicio = inicio + interval '1 hour', fin = fin + interval '1 hour'");
                throw new AssertionError("La app no debería poder mover la franxa de una reserva");
            } catch (SQLException e) {
                assertThat(e.getSQLState()).as("insufficient_privilege").isEqualTo("42501");
            }
            c.rollback();
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
