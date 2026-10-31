package org.pistaberta.application.reserva;

import java.util.List;
import java.util.Optional;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.Reserva;
import org.pistaberta.domain.reserva.ReservaId;

/** Puerto de persistencia de reservas del tenant en contexto. Debe usarse dentro de una {@link Transaccion}. */
public interface ReservaRepository {

    /** Reservas activas (confirmadas) de la pista cuya franxa solapa con la dada. */
    List<Reserva> activasSolapadas(PistaId pista, Franxa franxa);

    /**
     * Guarda una reserva nueva.
     *
     * @throws org.pistaberta.domain.reserva.SlotNonDisponible si la BD detecta un solape (capa 2)
     * @throws org.pistaberta.domain.reserva.PistaNonExiste si la pista no existe en el tenant
     */
    void gardar(Reserva reserva);

    Optional<Reserva> buscar(ReservaId id);

    void actualizar(Reserva reserva);
}
