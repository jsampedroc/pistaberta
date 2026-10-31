package org.pistaberta.application.reserva;

import java.util.function.Supplier;

/** Puerto: ejecuta el traballo nunha transacción (mantén application sen Spring). Rollback si lanza. */
public interface Transaccion {

    <T> T executar(Supplier<T> traballo);
}
