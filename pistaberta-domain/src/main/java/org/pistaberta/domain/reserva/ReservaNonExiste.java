package org.pistaberta.domain.reserva;

/** A reserva non existe neste concello. */
public class ReservaNonExiste extends RuntimeException {

    public ReservaNonExiste(ReservaId reserva) {
        super("A reserva non existe neste concello: " + reserva);
    }
}
