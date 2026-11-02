package org.pistaberta.domain.reserva;

/** Cancelar unha reserva xa cancelada: o estado cancelada é terminal. */
public class ReservaXaCancelada extends RuntimeException {

    public ReservaXaCancelada(ReservaId reserva) {
        super("A reserva xa está cancelada: " + reserva);
    }
}
