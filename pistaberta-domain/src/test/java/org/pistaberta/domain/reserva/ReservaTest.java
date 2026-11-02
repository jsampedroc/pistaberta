package org.pistaberta.domain.reserva;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservaTest {

    private static Reserva nova() {
        Instant t = Instant.parse("2026-11-02T10:00:00Z");
        return Reserva.confirmar(new PistaId(UUID.randomUUID()), new Franxa(t, t.plusSeconds(3600)));
    }

    @Test
    void newReservationIsConfirmedAndActive() {
        Reserva r = nova();
        assertThat(r.estado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(r.estaActiva()).isTrue();
    }

    @Test
    void cancellingFreesTheReservation() {
        Reserva r = nova();
        r.cancelar();
        assertThat(r.estado()).isEqualTo(EstadoReserva.CANCELADA);
        assertThat(r.estaActiva()).isFalse();
    }

    @Test
    void cancelledIsTerminal() {
        Reserva r = nova();
        r.cancelar();
        assertThatThrownBy(r::cancelar).isInstanceOf(ReservaXaCancelada.class);
    }
}
