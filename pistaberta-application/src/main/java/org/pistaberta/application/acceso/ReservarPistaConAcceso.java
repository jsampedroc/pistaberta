package org.pistaberta.application.acceso;

import org.pistaberta.application.reserva.ReservarPista;
import org.pistaberta.application.reserva.Transaccion;
import org.pistaberta.domain.acceso.TokenAcceso;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.Reserva;

/**
 * Reserva una pista y emite su token en la <b>misma transacción</b> (EX0): sin reserva no hay token y sin token no hay
 * reserva. {@link ReservarPista} abre su propia transacción, que aquí se une a la exterior.
 */
public class ReservarPistaConAcceso {

    private final ReservarPista reservar;
    private final EmitirAcceso emitir;
    private final Transaccion transaccion;

    public ReservarPistaConAcceso(ReservarPista reservar, EmitirAcceso emitir, Transaccion transaccion) {
        this.reservar = reservar;
        this.emitir = emitir;
        this.transaccion = transaccion;
    }

    public ReservaConAcceso executar(PistaId pista, Franxa franxa) {
        return transaccion.executar(() -> {
            Reserva reserva = reservar.executar(pista, franxa);
            TokenAcceso token = emitir.executar(reserva.id());
            return new ReservaConAcceso(reserva, token);
        });
    }
}
