package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.application.reserva.ReservaRepository;
import org.pistaberta.application.reserva.Transaccion;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.PistaNonExiste;
import org.pistaberta.domain.reserva.Reserva;
import org.pistaberta.domain.reserva.SlotNonDisponible;
import org.pistaberta.domain.tenant.TenantId;
import org.pistaberta.infrastructure.TestPostgres;
import org.pistaberta.infrastructure.tenant.ThreadLocalTenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** RV6 · el adaptador traduce las violaciones de la BD a errores de dominio; nunca sale SQL crudo. */
@SpringBootTest
@ActiveProfiles("test")
class JdbcReservaRepositoryTest {

    private static final UUID PISTA_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID PISTA_B = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final Instant T = Instant.parse("2026-11-02T10:00:00Z");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @Autowired
    ReservaRepository reservas;

    @Autowired
    Transaccion transaccion;

    @Autowired
    ThreadLocalTenantContext context;

    @BeforeEach
    void preparar() throws Exception {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        TestPostgres.insertPista(TestPostgres.SHARED, TestPostgres.A_ESTRADA, PISTA_A, "Pista A");
        TestPostgres.insertPista(TestPostgres.SHARED, TestPostgres.LALIN, PISTA_B, "Pista B");
        context.set(new TenantId(TestPostgres.A_ESTRADA));
    }

    @AfterEach
    void limpiar() {
        context.clear();
    }

    private static Reserva reserva(UUID pista, long inicioMin, long finMin) {
        return Reserva.confirmar(new PistaId(pista),
                new Franxa(T.plusSeconds(inicioMin * 60), T.plusSeconds(finMin * 60)));
    }

    /** Se salta la capa 1 (dominio) llamando directamente a gardar: la BD rechaza y el adaptador traduce. */
    @Test
    void overlapDetectedByTheDatabaseBecomesSlotNonDisponible() {
        transaccion.executar(() -> {
            reservas.gardar(reserva(PISTA_A, 0, 60));
            return null;
        });
        assertThatThrownBy(() -> transaccion.executar(() -> {
            reservas.gardar(reserva(PISTA_A, 30, 90));
            return null;
        })).isInstanceOf(SlotNonDisponible.class);
    }

    /** Una pista de otro tenant falla como una inexistente (FK compuesta), sin revelar que existe. */
    @Test
    void pistaOfAnotherTenantBecomesPistaNonExiste() {
        assertThatThrownBy(() -> transaccion.executar(() -> {
            reservas.gardar(reserva(PISTA_B, 0, 60));
            return null;
        })).isInstanceOf(PistaNonExiste.class);
    }
}
