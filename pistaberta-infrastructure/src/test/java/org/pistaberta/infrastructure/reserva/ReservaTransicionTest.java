package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.postgresql.util.PSQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F1 · transición terminal: una reserva cancelada no vuelve a confirmada (trigger de la BD). La franxa se deja
 * LIBRE a propósito: si estuviera ocupada, la exclusion constraint frenaría el UPDATE y el test sería mudo.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservaTransicionTest {

    private static final UUID TENANT = TestPostgres.A_ESTRADA;
    private static final UUID PISTA = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @BeforeEach
    void preparar() throws SQLException {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        TestPostgres.insertPista(TestPostgres.SHARED, TENANT, PISTA, "Pista 1");
    }

    @Test
    void aCancelledReservationCannotBeConfirmedAgainEvenIfTheFranxaIsFree() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            UUID id = TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "cancelada");

            try (Statement st = c.createStatement()) {
                st.executeUpdate("update reserva set estado = 'confirmada' where id = '" + id + "'");
                throw new AssertionError("cancelada -> confirmada debería estar prohibido (franxa libre)");
            } catch (PSQLException e) {
                assertThat(e.getSQLState()).as("check_violation").isEqualTo("23514");
                assertThat(e.getServerErrorMessage().getConstraint()).isEqualTo("reserva_non_reabrir");
            }
            assertThat(TestPostgres.count(c, "select count(*) from reserva where id = '" + id + "' and estado = 'cancelada'"))
                    .as("la reserva sigue cancelada").isEqualTo(1);
        }
    }

    /** La prohibición es solo esa transición: cancelar y los UPDATE neutros siguen permitidos. */
    @Test
    void cancellingAndNeutralUpdatesStillWork() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED); Statement st = c.createStatement()) {
            UUID id = TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            assertThat(st.executeUpdate("update reserva set estado = 'cancelada' where id = '" + id + "'")).isEqualTo(1);
            assertThat(st.executeUpdate("update reserva set estado = 'cancelada' where id = '" + id + "'")).isEqualTo(1);
        }
    }
}
