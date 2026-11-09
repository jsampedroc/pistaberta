package org.pistaberta.application.acceso;

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
import org.pistaberta.application.reserva.ReservaRepository;
import org.pistaberta.application.reserva.ReservarPista;
import org.pistaberta.application.reserva.Transaccion;
import org.pistaberta.domain.acceso.TokenAcceso;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.Reserva;
import org.pistaberta.domain.reserva.ReservaId;
import org.pistaberta.domain.reserva.SlotNonDisponible;

class ReservarPistaConAccesoTest {

    private static final Instant T = Instant.parse("2026-11-02T10:00:00Z");
    private static final PistaId PISTA = new PistaId(UUID.randomUUID());

    private final TransaccionConProfundidade transaccion = new TransaccionConProfundidade();
    private final ReservasEnMemoria reservas = new ReservasEnMemoria();
    private final AccesosEnMemoria accesos = new AccesosEnMemoria(transaccion);
    private int semilla = 0;
    private final GeneradorToken generador = () -> TokenAcceso.de(String.format("%043d", semilla++));
    private final ReservarPista reservar = new ReservarPista(reservas, transaccion);
    private final ReservarPistaConAcceso reservarConAcceso =
            new ReservarPistaConAcceso(reservar, new EmitirAcceso(generador, accesos), transaccion);

    private static Franxa franxa(long inicioMin, long finMin) {
        return new Franxa(T.plus(Duration.ofMinutes(inicioMin)), T.plus(Duration.ofMinutes(finMin)));
    }

    @Test
    void reservesAndReturnsTheTokenWhoseHashIsTheOnlyThingStored() {
        ReservaConAcceso r = reservarConAcceso.executar(PISTA, franxa(0, 60));
        assertThat(reservas.gardadas).containsExactly(r.reserva());
        assertThat(accesos.hashes).containsOnlyKeys(r.reserva().id());
        assertThat(r.token().coincideCon(accesos.hashes.get(r.reserva().id()))).isTrue();
    }

    @Test
    void noTokenIsIssuedWhenTheReservationIsRejected() {
        reservarConAcceso.executar(PISTA, franxa(0, 60));
        assertThatThrownBy(() -> reservarConAcceso.executar(PISTA, franxa(30, 90))).isInstanceOf(SlotNonDisponible.class);
        assertThat(accesos.hashes).as("un acceso por reserva confirmada").hasSize(1);
    }

    @Test
    void theAccessIsStoredInsideTheSameTransactionAsTheReservation() {
        reservarConAcceso.executar(PISTA, franxa(0, 60));
        assertThat(accesos.profundidadeAoGardar).as("transacción abierta al guardar el acceso").isPositive();
    }

    @Test
    void aFailureIssuingTheTokenPropagatesSoTheTransactionCanRollBack() {
        accesos.falla = true;
        assertThatThrownBy(() -> reservarConAcceso.executar(PISTA, franxa(0, 60)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final class TransaccionConProfundidade implements Transaccion {
        int profundidade;

        @Override
        public <R> R executar(Supplier<R> traballo) {
            profundidade++;
            try {
                return traballo.get();
            } finally {
                profundidade--;
            }
        }
    }

    private static final class AccesosEnMemoria implements RepositorioAcceso {
        final java.util.Map<ReservaId, byte[]> hashes = new java.util.HashMap<>();
        final TransaccionConProfundidade transaccion;
        int profundidadeAoGardar;
        boolean falla;

        AccesosEnMemoria(TransaccionConProfundidade transaccion) {
            this.transaccion = transaccion;
        }

        @Override
        public void gardar(ReservaId reserva, TokenAcceso token) {
            profundidadeAoGardar = transaccion.profundidade;
            if (falla) {
                throw new IllegalStateException("fallo simulado al guardar el acceso");
            }
            hashes.put(reserva, token.hash());
        }
    }

    private static final class ReservasEnMemoria implements ReservaRepository {
        final List<Reserva> gardadas = new ArrayList<>();

        @Override
        public List<Reserva> activasSolapadas(PistaId pista, Franxa franxa) {
            return gardadas.stream()
                    .filter(r -> r.estaActiva() && r.pistaId().equals(pista) && r.franxa().solapaCon(franxa))
                    .toList();
        }

        @Override
        public void gardar(Reserva reserva) {
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
