package org.pistaberta.application.acceso;

import java.util.Objects;
import org.pistaberta.domain.acceso.TokenAcceso;
import org.pistaberta.domain.reserva.Reserva;

/** Resultado de reservar: la reserva y su token, que solo se entrega ahora (después solo existe su hash). */
public record ReservaConAcceso(Reserva reserva, TokenAcceso token) {

    public ReservaConAcceso {
        Objects.requireNonNull(reserva, "reserva");
        Objects.requireNonNull(token, "token");
    }
}
