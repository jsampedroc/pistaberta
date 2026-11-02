package org.pistaberta.domain.reserva;

import java.util.Objects;

/** Aggregate: ocupación de una pista durante una franxa. No conoce el tenant (es cosa de infraestructura). */
public final class Reserva {

    private final ReservaId id;
    private final PistaId pistaId;
    private final Franxa franxa;
    private EstadoReserva estado;

    private Reserva(ReservaId id, PistaId pistaId, Franxa franxa, EstadoReserva estado) {
        this.id = Objects.requireNonNull(id, "id");
        this.pistaId = Objects.requireNonNull(pistaId, "pistaId");
        this.franxa = Objects.requireNonNull(franxa, "franxa");
        this.estado = Objects.requireNonNull(estado, "estado");
    }

    public static Reserva confirmar(PistaId pista, Franxa franxa) {
        return new Reserva(ReservaId.novo(), pista, franxa, EstadoReserva.CONFIRMADA);
    }

    /** Rehidratación desde persistencia. */
    public static Reserva reconstruir(ReservaId id, PistaId pista, Franxa franxa, EstadoReserva estado) {
        return new Reserva(id, pista, franxa, estado);
    }

    /** Cancelar libera la franxa. Cancelada es terminal: cancelar de nuevo es un error de dominio. */
    public void cancelar() {
        if (estado == EstadoReserva.CANCELADA) {
            throw new ReservaXaCancelada(id);
        }
        estado = EstadoReserva.CANCELADA;
    }

    public boolean estaActiva() {
        return estado == EstadoReserva.CONFIRMADA;
    }

    public ReservaId id() {
        return id;
    }

    public PistaId pistaId() {
        return pistaId;
    }

    public Franxa franxa() {
        return franxa;
    }

    public EstadoReserva estado() {
        return estado;
    }
}
