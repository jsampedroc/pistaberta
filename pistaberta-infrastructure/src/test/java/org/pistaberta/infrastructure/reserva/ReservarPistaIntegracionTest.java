package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.application.reserva.CancelarReserva;
import org.pistaberta.application.reserva.ReservarPista;
import org.pistaberta.domain.reserva.EstadoReserva;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.PistaNonExiste;
import org.pistaberta.domain.reserva.Reserva;
import org.pistaberta.domain.reserva.ReservaNonExiste;
import org.pistaberta.domain.reserva.ReservaXaCancelada;
import org.pistaberta.domain.reserva.SlotNonDisponible;
import org.pistaberta.domain.tenant.TenantId;
import org.pistaberta.infrastructure.TestPostgres;
import org.pistaberta.infrastructure.tenant.ThreadLocalTenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Casos de uso de punta a punta contra la BD real, con la app conectada como pistaberta_app (RLS activa). */
@SpringBootTest
@ActiveProfiles("test")
class ReservarPistaIntegracionTest {

    private static final UUID PISTA_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID PISTA_B = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final Instant T = Instant.parse("2026-11-02T10:00:00Z");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @Autowired
    ReservarPista reservar;

    @Autowired
    CancelarReserva cancelar;

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

    private static Franxa franxa(long inicioMin, long finMin) {
        return new Franxa(T.plusSeconds(inicioMin * 60), T.plusSeconds(finMin * 60));
    }

    private static long filas(String condicion) throws Exception {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            return TestPostgres.count(c, "select count(*) from reserva where " + condicion);
        }
    }

    @Test
    void reservesAFreeFranxaAndPersistsIt() throws Exception {
        Reserva r = reservar.executar(new PistaId(PISTA_A), franxa(0, 60));
        assertThat(filas("id = '" + r.id() + "' and estado = 'confirmada'")).isEqualTo(1);
    }

    @Test
    void overlappingReservationIsRejectedAndNothingIsPersisted() throws Exception {
        reservar.executar(new PistaId(PISTA_A), franxa(0, 60));
        assertThatThrownBy(() -> reservar.executar(new PistaId(PISTA_A), franxa(30, 90))).isInstanceOf(SlotNonDisponible.class);
        assertThat(filas("pista_id = '" + PISTA_A + "'")).isEqualTo(1);
    }

    @Test
    void reservingAPistaOfAnotherTenantFails() {
        assertThatThrownBy(() -> reservar.executar(new PistaId(PISTA_B), franxa(0, 60))).isInstanceOf(PistaNonExiste.class);
    }

    @Test
    void cancellingPersistsTheNewStateAndIsTerminal() throws Exception {
        Reserva r = reservar.executar(new PistaId(PISTA_A), franxa(0, 60));
        assertThat(cancelar.executar(r.id()).estado()).isEqualTo(EstadoReserva.CANCELADA);
        assertThat(filas("id = '" + r.id() + "' and estado = 'cancelada'")).isEqualTo(1);
        assertThatThrownBy(() -> cancelar.executar(r.id())).isInstanceOf(ReservaXaCancelada.class);
    }

    @Test
    void anotherTenantCannotCancelAReservation() {
        Reserva r = reservar.executar(new PistaId(PISTA_A), franxa(0, 60));
        context.set(new TenantId(TestPostgres.LALIN));
        assertThatThrownBy(() -> cancelar.executar(r.id())).isInstanceOf(ReservaNonExiste.class);
    }
}
