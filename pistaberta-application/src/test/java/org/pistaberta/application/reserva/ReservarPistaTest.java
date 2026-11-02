package org.pistaberta.application.reserva;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.pistaberta.domain.reserva.EstadoReserva;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.Reserva;
import org.pistaberta.domain.reserva.ReservaId;
import org.pistaberta.domain.reserva.ReservaNonExiste;
import org.pistaberta.domain.reserva.ReservaXaCancelada;
import org.pistaberta.domain.reserva.SlotNonDisponible;

class ReservarPistaTest {

    private static final Instant T = Instant.parse("2026-11-02T10:00:00Z");
    private static final PistaId PISTA = new PistaId(UUID.randomUUID());

    private final RepositorioEnMemoria repo = new RepositorioEnMemoria();
    private final Transaccion transaccion = new Transaccion() {
        @Override
        public <R> R executar(Supplier<R> traballo) {
            return traballo.get();
        }
    };
    private final ReservarPista reservar = new ReservarPista(repo, transaccion);
    private final CancelarReserva cancelar = new CancelarReserva(repo, transaccion);

    private static Franxa franxa(long inicioMin, long finMin) {
        return new Franxa(T.plus(Duration.ofMinutes(inicioMin)), T.plus(Duration.ofMinutes(finMin)));
    }

    @Test
    void reservesAFreeFranxa() {
        Reserva r = reservar.executar(PISTA, franxa(0, 60));
        assertThat(r.estado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(repo.gardadas).containsExactly(r);
    }

    /** Capa 1: el dominio rechaza sin llegar a guardar. */
    @Test
    void overlappingReservationIsRejectedBeforeReachingThePersistence() {
        reservar.executar(PISTA, franxa(0, 60));
        assertThatThrownBy(() -> reservar.executar(PISTA, franxa(30, 90))).isInstanceOf(SlotNonDisponible.class);
        assertThat(repo.intentosDeGardar).as("no se intenta guardar la solapada").isEqualTo(1);
    }

    @Test
    void cancellingMarksTheReservationCancelled() {
        Reserva r = reservar.executar(PISTA, franxa(0, 60));
        assertThat(cancelar.executar(r.id()).estado()).isEqualTo(EstadoReserva.CANCELADA);
    }

    @Test
    void cancellingTwiceFails() {
        Reserva r = reservar.executar(PISTA, franxa(0, 60));
        cancelar.executar(r.id());
        assertThatThrownBy(() -> cancelar.executar(r.id())).isInstanceOf(ReservaXaCancelada.class);
    }

    @Test
    void cancellingAnUnknownReservationFails() {
        assertThatThrownBy(() -> cancelar.executar(ReservaId.novo())).isInstanceOf(ReservaNonExiste.class);
    }

    /** Repositorio en memoria que, como la BD, rechaza solapes en gardar (simula la capa 2). */
    private static final class RepositorioEnMemoria implements ReservaRepository {
        final List<Reserva> gardadas = new ArrayList<>();
        int intentosDeGardar;

        @Override
        public List<Reserva> activasSolapadas(PistaId pista, Franxa franxa) {
            return gardadas.stream()
                    .filter(r -> r.estaActiva() && r.pistaId().equals(pista) && r.franxa().solapaCon(franxa))
                    .toList();
        }

        @Override
        public void gardar(Reserva reserva) {
            intentosDeGardar++;
            if (!activasSolapadas(reserva.pistaId(), reserva.franxa()).isEmpty()) {
                throw new SlotNonDisponible(reserva.franxa());
            }
            gardadas.add(reserva);
        }

        @Override
        public Optional<Reserva> buscar(ReservaId id) {
            return gardadas.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public void actualizar(Reserva reserva) {}
    }
}
