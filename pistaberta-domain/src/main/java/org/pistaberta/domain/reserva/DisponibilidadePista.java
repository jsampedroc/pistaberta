package org.pistaberta.domain.reserva;

import java.util.Collection;

/** Servicio de dominio: capa 1 de la invariante de no-solapamiento (la capa 2 es la exclusion constraint de la BD). */
public final class DisponibilidadePista {

    private DisponibilidadePista() {}

    /** Falla rápido con SlotNonDisponible si la franxa choca con alguna reserva activa de la pista. */
    public static void comprobarLibre(Franxa pedida, Collection<Reserva> activas) {
        for (Reserva reserva : activas) {
            if (reserva.estaActiva() && reserva.franxa().solapaCon(pedida)) {
                throw new SlotNonDisponible(pedida);
            }
        }
    }
}
