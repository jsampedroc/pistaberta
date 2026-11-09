package org.pistaberta.application.acceso;

import org.pistaberta.domain.acceso.TokenAcceso;
import org.pistaberta.domain.reserva.ReservaId;

/** Puerto: persistencia del acceso (solo el hash del token) de una reserva del tenant actual. */
public interface RepositorioAcceso {

    /** Guarda el hash del token para la reserva. El token en claro nunca se persiste. */
    void gardar(ReservaId reserva, TokenAcceso token);
}
