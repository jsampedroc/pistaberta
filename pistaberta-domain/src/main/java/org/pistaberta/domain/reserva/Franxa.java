package org.pistaberta.domain.reserva;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Intervalo semiabierto [inicio, fin) en instantes absolutos. Se normaliza a microsegundos, la precisión de
 * Postgres, para que dominio y BD coincidan en el borde. Las franxas adxacentes (fin == inicio) no solapan.
 */
public record Franxa(Instant inicio, Instant fin) {

    public Franxa {
        Objects.requireNonNull(inicio, "inicio");
        Objects.requireNonNull(fin, "fin");
        inicio = inicio.truncatedTo(ChronoUnit.MICROS);
        fin = fin.truncatedTo(ChronoUnit.MICROS);
        if (!fin.isAfter(inicio)) {
            throw new FranxaInvalida("A franxa debe rematar despois de comezar: [" + inicio + ", " + fin + ")");
        }
    }

    public boolean solapaCon(Franxa outra) {
        return inicio.isBefore(outra.fin) && outra.inicio.isBefore(fin);
    }
}
