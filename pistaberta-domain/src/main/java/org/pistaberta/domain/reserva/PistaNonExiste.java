package org.pistaberta.domain.reserva;

/** A pista non existe neste concello. */
public class PistaNonExiste extends RuntimeException {

    public PistaNonExiste(PistaId pista, Throwable causa) {
        super("A pista non existe neste concello: " + pista, causa);
    }
}
