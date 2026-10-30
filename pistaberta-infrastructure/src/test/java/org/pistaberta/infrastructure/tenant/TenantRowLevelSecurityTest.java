package org.pistaberta.infrastructure.tenant;

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

/** R1 · RLS: el rol de la app, con GUC=A, no puede leer ni afectar filas de B aunque la query no filtre. */
@SpringBootTest
@ActiveProfiles("test")
class TenantRowLevelSecurityTest {

    private static final UUID A = TestPostgres.A_ESTRADA;
    private static final UUID B = TestPostgres.LALIN;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @BeforeEach
    void rows() throws SQLException {
        TestPostgres.resetProbe(TestPostgres.SHARED, 2, 2);
    }

    @Test
    void rlsHidesAndProtectsOtherTenantRows() throws SQLException {
        try (Connection c = TestPostgres.appRole(TestPostgres.SHARED)) {
            c.setAutoCommit(false);

            // Anti verde mudo: el rol de la conexión no puede saltarse RLS.
            assertThat(TestPostgres.count(c,
                    "select count(*) from pg_roles where rolname = current_user and not rolsuper and not rolbypassrls"))
                    .as("rolsuper=false y rolbypassrls=false").isEqualTo(1);

            // Fail-closed: sin GUC no se ve ninguna fila.
            assertThat(TestPostgres.count(c, "select count(*) from tenant_probe")).isZero();

            setTenant(c, A);
            // Query cruda SIN filtro: solo salen las de A.
            assertThat(TestPostgres.count(c, "select count(*) from tenant_probe")).isEqualTo(2);
            // Pidiendo explícitamente las de B: 0 filas.
            assertThat(TestPostgres.count(c, "select count(*) from tenant_probe where tenant_id = '" + B + "'")).isZero();

            try (Statement st = c.createStatement()) {
                assertThat(st.executeUpdate("update tenant_probe set etiqueta = 'hack' where tenant_id = '" + B + "'"))
                        .as("UPDATE sobre B").isZero();
                assertThat(st.executeUpdate("delete from tenant_probe where tenant_id = '" + B + "'"))
                        .as("DELETE sobre B").isZero();
            }

            Savepoint sp = c.setSavepoint();
            try (PreparedStatement ps = c.prepareStatement("insert into tenant_probe (tenant_id, etiqueta) values (?, 'x')")) {
                ps.setObject(1, B);
                ps.executeUpdate();
                throw new AssertionError("INSERT con tenant B debería violar la policy");
            } catch (SQLException e) {
                assertThat(e.getSQLState()).as("row-level security").isEqualTo("42501");
            }
            c.rollback(sp);
            c.rollback();
        }

        try (Connection su = TestPostgres.superuser(TestPostgres.SHARED)) {
            assertThat(TestPostgres.count(su, "select count(*) from tenant_probe where tenant_id = '" + B + "'"))
                    .as("filas de B intactas").isEqualTo(2);
            assertThat(TestPostgres.count(su, "select count(*) from tenant_probe where etiqueta = 'hack'")).isZero();
        }
    }

    private static void setTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }
}
