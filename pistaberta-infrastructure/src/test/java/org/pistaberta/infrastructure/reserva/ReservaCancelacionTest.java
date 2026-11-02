package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Tier BD · RV5: la constraint es parcial (WHERE estado = 'confirmada'), así que cancelar libera la franxa. */
@SpringBootTest
@ActiveProfiles("test")
class ReservaCancelacionTest {

    private static final UUID TENANT = TestPostgres.A_ESTRADA;
    private static final UUID PISTA = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @BeforeEach
    void pista() throws SQLException {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        TestPostgres.insertPista(TestPostgres.SHARED, TENANT, PISTA, "Pista 1");
    }

    @Test
    void cancellingFreesTheFranxaForANewReservation() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED); Statement st = c.createStatement()) {
            UUID primera = TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            st.executeUpdate("update reserva set estado = 'cancelada' where id = '" + primera + "'");

            TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");

            assertThat(TestPostgres.count(c, "select count(*) from reserva where estado = 'confirmada'")).isEqualTo(1);
            assertThat(TestPostgres.count(c, "select count(*) from reserva where estado = 'cancelada'")).isEqualTo(1);
        }
    }
}
