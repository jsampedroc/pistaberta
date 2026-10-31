package org.pistaberta.application.reserva;

import java.util.List;
import org.pistaberta.domain.reserva.DisponibilidadePista;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.Reserva;

/**
 * Caso de uso: reservar una pista. Capa 1 (dominio) falla rápido; capa 2 (exclusion constraint) es la última
 * palabra si otra transacción se adelanta entre la lectura y el insert. Ambas lanzan SlotNonDisponible.
 */
public class ReservarPista {

    private final ReservaRepository reservas;
    private final Transaccion transaccion;

    public ReservarPista(ReservaRepository reservas, Transaccion transaccion) {
        this.reservas = reservas;
        this.transaccion = transaccion;
    }

    public Reserva executar(PistaId pista, Franxa franxa) {
        return transaccion.executar(() -> {
            List<Reserva> activas = reservas.activasSolapadas(pista, franxa);
            DisponibilidadePista.comprobarLibre(franxa, activas);
            Reserva reserva = Reserva.confirmar(pista, franxa);
            reservas.gardar(reserva);
            return reserva;
        });
    }
}
