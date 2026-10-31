package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** RV7 · RLS sobre pista y reserva con el rol de la app, y FK compuesta (tenant_id, pista_id). */
@SpringBootTest
@ActiveProfiles("test")
class ReservaTenantTest {

    private static final UUID A = TestPostgres.A_ESTRADA;
    private static final UUID B = TestPostgres.LALIN;
    private static final UUID PISTA_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID PISTA_B = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @BeforeEach
    void preparar() throws SQLException {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        TestPostgres.insertPista(TestPostgres.SHARED, A, PISTA_A, "Pista A");
        TestPostgres.insertPista(TestPostgres.SHARED, B, PISTA_B, "Pista B");
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            TestPostgres.insertReserva(c, A, PISTA_A, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            TestPostgres.insertReserva(c, B, PISTA_B, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
        }
    }

    @Test
    void tenantDoesNotSeeNorAffectOtherTenantsRows() throws SQLException {
        try (Connection c = TestPostgres.appRole(TestPostgres.SHARED)) {
            c.setAutoCommit(false);
            assertThat(TestPostgres.count(c, "select count(*) from reserva")).as("sin tenant: fail-closed").isZero();
            assertThat(TestPostgres.count(c, "select count(*) from pista")).isZero();

            setTenant(c, A);
            assertThat(TestPostgres.count(c, "select count(*) from reserva")).isEqualTo(1);
            assertThat(TestPostgres.count(c, "select count(*) from reserva where tenant_id = '" + B + "'")).isZero();
            assertThat(TestPostgres.count(c, "select count(*) from pista where tenant_id = '" + B + "'")).isZero();
            try (Statement st = c.createStatement()) {
                assertThat(st.executeUpdate("update reserva set estado = 'cancelada' where tenant_id = '" + B + "'")).isZero();
            }
            c.rollback();
        }
        try (Connection su = TestPostgres.superuser(TestPostgres.SHARED)) {
            assertThat(TestPostgres.count(su, "select count(*) from reserva where tenant_id = '" + B + "' and estado = 'confirmada'"))
                    .as("reserva de B intacta").isEqualTo(1);
        }
    }

    /** La FK compuesta impide apuntar a la pista de otro tenant aunque el tenant_id de la fila sea el propio. */
    @Test
    void reservationCannotPointToAPistaOfAnotherTenant() throws SQLException {
        try (Connection c = TestPostgres.appRole(TestPostgres.SHARED)) {
            c.setAutoCommit(false);
            setTenant(c, A);
            Savepoint sp = c.setSavepoint();
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into reserva (id, tenant_id, pista_id, inicio, fin, estado) values (?, ?, ?, now(), now() + interval '1 hour', 'confirmada')")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, A);
                ps.setObject(3, PISTA_B);
                ps.executeUpdate();
                throw new AssertionError("La FK compuesta debería rechazar la pista de otro tenant");
            } catch (SQLException e) {
                assertThat(e.getSQLState()).as("foreign_key_violation").isEqualTo("23503");
            }
            c.rollback(sp);
            c.rollback();
        }
    }

    private static void setTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }
}
