package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.postgresql.util.PSQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Tier BD · RV2 (solape saltándose el dominio), RV3b (adyacentes) y RV9 (franxa vacía) sobre SQL crudo. */
@SpringBootTest
@ActiveProfiles("test")
class ReservaExclusionConstraintTest {

    private static final UUID TENANT = TestPostgres.A_ESTRADA;
    private static final UUID PISTA = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID OTRA_PISTA = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @BeforeEach
    void pistas() throws SQLException {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        TestPostgres.insertPista(TestPostgres.SHARED, TENANT, PISTA, "Pista 1");
        TestPostgres.insertPista(TestPostgres.SHARED, TENANT, OTRA_PISTA, "Pista 2");
    }

    /** RV2: la BD rechaza la 2ª reserva solapada aunque el dominio no haya intervenido. */
    @Test
    void secondOverlappingInsertIsRejectedByTheDatabase() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");

            SQLException fallo = intentar(
                    () -> TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:30:00Z", "2026-11-02T11:30:00Z", "confirmada"));

            long filas = TestPostgres.count(c, "select count(*) from reserva where pista_id = '" + PISTA + "'");
            assertThat((Throwable) fallo).as("2ª inserción solapada: filas en la pista=%d (esperado 1)", filas).isNotNull();
            assertThat(fallo.getSQLState()).isEqualTo("23P01");
            assertThat(((PSQLException) fallo).getServerErrorMessage().getConstraint()).isEqualTo(JdbcReservaRepository.CONSTRAINT_SOLAPE);
            assertThat(filas).isEqualTo(1);
        }
    }

    /** RV3b: fin == inicio ajeno NO solapa. */
    @Test
    void adjacentReservationsAreAllowed() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T11:00:00Z", "2026-11-02T12:00:00Z", "confirmada");
            assertThat(TestPostgres.count(c, "select count(*) from reserva where pista_id = '" + PISTA + "'")).isEqualTo(2);
        }
    }

    @Test
    void sameSlotOnAnotherPistaIsAllowed() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            TestPostgres.insertReserva(c, TENANT, OTRA_PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            assertThat(TestPostgres.count(c, "select count(*) from reserva")).isEqualTo(2);
        }
    }

    /** RV9: la exclusion no ve el rango vacío; lo frena el CHECK (fin > inicio). */
    @Test
    void emptyFranxaIsRejectedByTheCheckConstraint() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            SQLException fallo = intentar(
                    () -> TestPostgres.insertReserva(c, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T10:00:00Z", "confirmada"));
            assertThat((Throwable) fallo).isNotNull();
            assertThat(fallo.getSQLState()).isEqualTo("23514");
            assertThat(((PSQLException) fallo).getServerErrorMessage().getConstraint()).isEqualTo("reserva_franxa_ck");
        }
    }

    private interface Accion {
        void ejecutar() throws SQLException;
    }

    /** Devuelve la SQLException que lanza la acción, o null si termina sin error. */
    private static SQLException intentar(Accion accion) {
        try {
            accion.ejecutar();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }
}
