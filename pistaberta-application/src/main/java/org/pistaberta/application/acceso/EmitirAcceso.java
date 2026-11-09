package org.pistaberta.application.acceso;

import org.pistaberta.domain.acceso.TokenAcceso;
import org.pistaberta.domain.reserva.ReservaId;

/**
 * Emite el token de acceso de una reserva (D-011). No abre transacción: se ejecuta dentro de la de
 * {@link ReservarPistaConAcceso}, para que reserva y acceso se confirmen o se deshagan juntos.
 */
public class EmitirAcceso {

    private final GeneradorToken generador;
    private final RepositorioAcceso accesos;

    public EmitirAcceso(GeneradorToken generador, RepositorioAcceso accesos) {
        this.generador = generador;
        this.accesos = accesos;
    }

    public TokenAcceso executar(ReservaId reserva) {
        TokenAcceso token = generador.xerar();
        accesos.gardar(reserva, token);
        return token;
    }
}
