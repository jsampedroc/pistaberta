package org.pistaberta.domain.reserva;

/** La franxa no cumple fin > inicio. */
public class FranxaInvalida extends RuntimeException {

    public FranxaInvalida(String mensaxe) {
        super(mensaxe);
    }
}
