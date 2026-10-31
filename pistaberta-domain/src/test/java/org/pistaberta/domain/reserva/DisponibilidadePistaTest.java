package org.pistaberta.domain.reserva;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisponibilidadePistaTest {

    private static final Instant T = Instant.parse("2026-11-02T10:00:00Z");
    private static final PistaId PISTA = new PistaId(UUID.randomUUID());

    private static Franxa franxa(long inicioMin, long finMin) {
        return new Franxa(T.plus(Duration.ofMinutes(inicioMin)), T.plus(Duration.ofMinutes(finMin)));
    }

    /** RV1: dos reservas solapadas en la misma pista → la 2ª se rechaza con SlotNonDisponible. */
    @Test
    void overlappingFranxaOnTheSamePistaIsRejected() {
        Reserva existente = Reserva.confirmar(PISTA, franxa(0, 60));
        assertThatThrownBy(() -> DisponibilidadePista.comprobarLibre(franxa(30, 90), List.of(existente)))
                .isInstanceOf(SlotNonDisponible.class);
    }

    @Test
    void freeFranxaIsAccepted() {
        Reserva existente = Reserva.confirmar(PISTA, franxa(0, 60));
        assertThatCode(() -> DisponibilidadePista.comprobarLibre(franxa(120, 180), List.of(existente)))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelledReservationsDoNotBlock() {
        Reserva cancelada = Reserva.confirmar(PISTA, franxa(0, 60));
        cancelada.cancelar();
        assertThatCode(() -> DisponibilidadePista.comprobarLibre(franxa(0, 60), List.of(cancelada)))
                .doesNotThrowAnyException();
    }
}
