package org.pistaberta.domain.reserva;

/** Una reserva confirmada ocupa la franxa; una cancelada la libera y es terminal. */
public enum EstadoReserva {
    CONFIRMADA,
    CANCELADA
}
