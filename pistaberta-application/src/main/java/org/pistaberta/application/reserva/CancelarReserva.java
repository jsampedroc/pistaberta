package org.pistaberta.application.reserva;

import org.pistaberta.domain.reserva.Reserva;
import org.pistaberta.domain.reserva.ReservaId;
import org.pistaberta.domain.reserva.ReservaNonExiste;

/** Caso de uso: cancelar una reserva (cambio de estado, no borrado), lo que libera la franxa. */
public class CancelarReserva {

    private final ReservaRepository reservas;
    private final Transaccion transaccion;

    public CancelarReserva(ReservaRepository reservas, Transaccion transaccion) {
        this.reservas = reservas;
        this.transaccion = transaccion;
    }

    public Reserva executar(ReservaId id) {
        return transaccion.executar(() -> {
            Reserva reserva = reservas.buscar(id).orElseThrow(() -> new ReservaNonExiste(id));
            reserva.cancelar();
            reservas.actualizar(reserva);
            return reserva;
        });
    }
}
