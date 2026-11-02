package org.pistaberta.domain.reserva;

import java.util.Objects;
import java.util.UUID;

/** Identificador de una reserva; lo genera la aplicación, no la BD (D-007). */
public record ReservaId(UUID value) {

    public ReservaId {
        Objects.requireNonNull(value, "value");
    }

    public static ReservaId novo() {
        return new ReservaId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
