package org.pistaberta.domain.reserva;

import java.util.Objects;
import java.util.UUID;

/** Identificador de una pista (espazo reservable). */
public record PistaId(UUID value) {

    public PistaId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
