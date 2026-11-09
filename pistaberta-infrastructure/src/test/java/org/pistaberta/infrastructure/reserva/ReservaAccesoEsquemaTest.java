package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** EX0/EX6 en la BD: V4 {@code reserva_acceso}. Cada test tiene su mutación aislada (ver SDD-extras §6). */
@SpringBootTest
@ActiveProfiles("test")
class ReservaAccesoEsquemaTest {

    private static final UUID A = TestPostgres.A_ESTRADA;
    private static final UUID B = TestPostgres.LALIN;
    private static final UUID PISTA_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID PISTA_B = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final byte[] HASH_1 = hash(1);
    private static final byte[] HASH_2 = hash(2);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    private UUID reservaA1;
    private UUID reservaA2;
    private UUID reservaB1;

    @BeforeEach
    void preparar() throws SQLException {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        TestPostgres.insertPista(TestPostgres.SHARED, A, PISTA_A, "Pista A");
        TestPostgres.insertPista(TestPostgres.SHARED, B, PISTA_B, "Pista B");
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            reservaA1 = TestPostgres.insertReserva(c, A, PISTA_A, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            reservaA2 = TestPostgres.insertReserva(c, A, PISTA_A, "2026-11-02T12:00:00Z", "2026-11-02T13:00:00Z", "confirmada");
            reservaB1 = TestPostgres.insertReserva(c, B, PISTA_B, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
        }
    }

    private static byte[] hash(int semilla) {
        byte[] h = new byte[32];
        java.util.Arrays.fill(h, (byte) semilla);
        return h;
    }

    private static void insertarAcceso(Connection c, UUID tenant, UUID reserva, byte[] hash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into reserva_acceso (tenant_id, reserva_id, token_hash) values (?, ?, ?)")) {
            ps.setObject(1, tenant);
            ps.setObject(2, reserva);
            ps.setBytes(3, hash);
            ps.executeUpdate();
        }
    }

    /** SQLSTATE de la violación, o {@code null} si el insert triunfó (conexión en autocommit, sin savepoints). */
    private static String estadoAlInsertar(UUID tenant, UUID reserva, byte[] hash) throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            insertarAcceso(c, tenant, reserva, hash);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static boolean puede(Connection c, String privilegio) throws SQLException {
        return TestPostgres.count(c, "select case when has_table_privilege('pistaberta_app', 'public.reserva_acceso', '"
                + privilegio + "') then 1 else 0 end") == 1;
    }

    @Test
    void appCanOnlyReadAndInsertAccesses() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            assertThat(puede(c, "SELECT")).isTrue();
            assertThat(puede(c, "INSERT")).isTrue();
            assertThat(puede(c, "UPDATE")).as("los tokens son inmutables").isFalse();
            assertThat(puede(c, "DELETE")).as("los tokens no se borran").isFalse();
            assertThat(puede(c, "TRUNCATE")).isFalse();
        }
    }

    @Test
    void tokenHashMustBeExactly32Bytes() throws SQLException {
        assertThat(estadoAlInsertar(A, reservaA1, new byte[31])).as("check_violation").isEqualTo("23514");
        assertThat(estadoAlInsertar(A, reservaA1, new byte[43])).as("el token en claro (43 bytes) no cabe").isEqualTo("23514");
        assertThat(estadoAlInsertar(A, reservaA1, HASH_1)).isNull();
    }

    @Test
    void accessMustPointToAnExistingReservationOfTheSameTenant() throws SQLException {
        assertThat(estadoAlInsertar(A, UUID.randomUUID(), HASH_1)).as("reserva inexistente").isEqualTo("23503");
        assertThat(estadoAlInsertar(A, reservaB1, HASH_1)).as("reserva de otro tenant (FK compuesta)").isEqualTo("23503");
    }

    @Test
    void aReservationHasAtMostOneAccess() throws SQLException {
        assertThat(estadoAlInsertar(A, reservaA1, HASH_1)).isNull();
        assertThat(estadoAlInsertar(A, reservaA1, HASH_2)).as("unique_violation (PK)").isEqualTo("23505");
    }

    @Test
    void tokenHashIsUniquePerTenantButNotGlobally() throws SQLException {
        assertThat(estadoAlInsertar(A, reservaA1, HASH_1)).isNull();
        assertThat(estadoAlInsertar(A, reservaA2, HASH_1)).as("mismo hash, mismo tenant").isEqualTo("23505");
        assertThat(estadoAlInsertar(B, reservaB1, HASH_1)).as("mismo hash, otro tenant: permitido").isNull();
    }

    private static void fijarTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }

    @Test
    void rowLevelSecurityIsolatesAccessesBetweenTenants() throws SQLException {
        assertThat(estadoAlInsertar(A, reservaA1, HASH_1)).isNull();
        assertThat(estadoAlInsertar(B, reservaB1, HASH_2)).isNull();
        try (Connection c = TestPostgres.appRole(TestPostgres.SHARED)) {
            c.setAutoCommit(false);
            assertThat(TestPostgres.count(c, "select count(*) from reserva_acceso")).as("sin tenant: fail-closed").isZero();
            fijarTenant(c, A);
            assertThat(TestPostgres.count(c, "select count(*) from reserva_acceso")).isEqualTo(1);
            assertThat(TestPostgres.count(c, "select count(*) from reserva_acceso where tenant_id = '" + B + "'")).isZero();
            c.rollback();
        }
        try (Connection c = TestPostgres.appRole(TestPostgres.SHARED)) {
            c.setAutoCommit(false);
            fijarTenant(c, B);
            assertThat(TestPostgres.count(c, "select count(*) from reserva_acceso")).isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    void appCannotInsertAnAccessForAnotherTenant() throws SQLException {
        try (Connection c = TestPostgres.appRole(TestPostgres.SHARED)) {
            c.setAutoCommit(false);
            fijarTenant(c, A);
            try {
                insertarAcceso(c, B, reservaB1, HASH_1);
                throw new AssertionError("La policy WITH CHECK debería rechazar un tenant ajeno");
            } catch (SQLException e) {
                assertThat(e.getSQLState()).as("insufficient_privilege (RLS)").isEqualTo("42501");
            }
            c.rollback();
        }
    }
}
