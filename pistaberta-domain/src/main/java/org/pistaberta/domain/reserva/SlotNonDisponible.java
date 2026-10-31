package org.pistaberta.domain.reserva;

/** A franxa pedida choca cunha reserva confirmada da mesma pista. */
public class SlotNonDisponible extends RuntimeException {

    public SlotNonDisponible(Franxa franxa) {
        super("Franxa non dispoñible: [" + franxa.inicio() + ", " + franxa.fin() + ")");
    }

    public SlotNonDisponible(Franxa franxa, Throwable causa) {
        super("Franxa non dispoñible: [" + franxa.inicio() + ", " + franxa.fin() + ")", causa);
    }
}
