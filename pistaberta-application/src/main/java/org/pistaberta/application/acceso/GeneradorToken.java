package org.pistaberta.application.acceso;

import org.pistaberta.domain.acceso.TokenAcceso;

/** Puerto: fuente de tokens opacos (en infraestructura, {@code SecureRandom} de 256 bits). */
public interface GeneradorToken {

    TokenAcceso xerar();
}
