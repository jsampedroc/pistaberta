package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tier BD · RV4: dos inserts solapados concurrentes → exactamente uno triunfa porque la BD serializa.
 *
 * <p>Su evidencia propia es el aserto sobre pg_stat_activity: la 2ª transacción queda BLOQUEADA
 * (wait_event_type = 'Lock') hasta que la 1ª termina. Un RV4 verde sin ese aserto es verde mudo y no cuenta.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservaConcurrenciaTest {

    private static final UUID TENANT = TestPostgres.A_ESTRADA;
    private static final UUID PISTA = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String BLOQUEADA = "RV4: la 2ª transacción debe quedar BLOQUEADA (pg_stat_activity wait_event_type=Lock)";

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
    void secondTransactionBlocksAndFailsWhenTheFirstCommits() throws Exception {
        try (Connection tx1 = TestPostgres.superuser(TestPostgres.SHARED)) {
            tx1.setAutoCommit(false);
            TestPostgres.insertReserva(tx1, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            // MUT-RV4b: aquí tx1 sigue SIN confirmar mientras la 2ª transacción intenta insertar

            SegundaTransaccion tx2 = SegundaTransaccion.lanzar("2026-11-02T10:30:00Z", "2026-11-02T11:30:00Z");
            boolean bloqueada = tx2.esperarBloqueo();
            tx1.commit();
            Throwable resultado = tx2.resultado();

            assertThat(bloqueada).as(BLOQUEADA).isTrue();
            assertThat(resultado).as("la 2ª transacción debe fallar al confirmar la 1ª").isInstanceOf(SQLException.class);
            assertThat(((SQLException) resultado).getSQLState()).isEqualTo("23P01");
            assertThat(confirmadas()).as("exactamente una reserva triunfa").isEqualTo(1);
        }
    }

    @Test
    void secondTransactionBlocksAndSucceedsWhenTheFirstRollsBack() throws Exception {
        try (Connection tx1 = TestPostgres.superuser(TestPostgres.SHARED)) {
            tx1.setAutoCommit(false);
            TestPostgres.insertReserva(tx1, TENANT, PISTA, "2026-11-02T10:00:00Z", "2026-11-02T11:00:00Z", "confirmada");
            // MUT-RV4b-rollback: aquí tx1 sigue SIN terminar mientras la 2ª transacción intenta insertar

            SegundaTransaccion tx2 = SegundaTransaccion.lanzar("2026-11-02T10:30:00Z", "2026-11-02T11:30:00Z");
            boolean bloqueada = tx2.esperarBloqueo();
            tx1.rollback();
            Throwable resultado = tx2.resultado();

            assertThat(bloqueada).as(BLOQUEADA).isTrue();
            assertThat(resultado).as("la 2ª transacción triunfa al hacer rollback la 1ª").isNull();
            assertThat(confirmadas()).as("exactamente una reserva triunfa").isEqualTo(1);
        }
    }

    private static long confirmadas() throws SQLException {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            return TestPostgres.count(c, "select count(*) from reserva where pista_id = '" + PISTA + "'");
        }
    }

    /** Segunda transacción en otro hilo y otra conexión; expone su pid para observarla en pg_stat_activity. */
    private static final class SegundaTransaccion {
        private final CompletableFuture<Integer> pid = new CompletableFuture<>();
        private final CompletableFuture<Throwable> resultado = new CompletableFuture<>();

        static SegundaTransaccion lanzar(String inicio, String fin) {
            SegundaTransaccion t = new SegundaTransaccion();
            Thread hilo = new Thread(() -> {
                try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
                    c.setAutoCommit(false);
                    t.pid.complete(pidDe(c));
                    TestPostgres.insertReserva(c, TENANT, PISTA, inicio, fin, "confirmada");
                    c.commit();
                    t.resultado.complete(null);
                } catch (Throwable e) {
                    t.pid.complete(-1);
                    t.resultado.complete(e);
                }
            }, "reserva-tx2");
            hilo.start();
            return t;
        }

        /** true si en algún momento la 2ª transacción aparece esperando un Lock; false si termina o vence el plazo. */
        boolean esperarBloqueo() throws Exception {
            int backend = pid.get(10, TimeUnit.SECONDS);
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try (Connection monitor = TestPostgres.superuser(TestPostgres.SHARED)) {
                while (System.nanoTime() < limite && !resultado.isDone()) {
                    if (TestPostgres.count(monitor, "select count(*) from pg_stat_activity where pid = " + backend
                            + " and wait_event_type = 'Lock'") == 1) {
                        return true;
                    }
                    Thread.sleep(50);
                }
            }
            return false;
        }

        Throwable resultado() throws Exception {
            return resultado.get(10, TimeUnit.SECONDS);
        }

        private static int pidDe(Connection c) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("select pg_backend_pid()"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
