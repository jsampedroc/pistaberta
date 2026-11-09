package org.pistaberta.infrastructure.acceso;

import java.security.SecureRandom;
import java.util.Base64;
import org.pistaberta.application.acceso.GeneradorToken;
import org.pistaberta.domain.acceso.TokenAcceso;
import org.springframework.stereotype.Component;

/** Tokens de 256 bits de {@link SecureRandom}, en Base64URL sin relleno (43 caracteres). */
@Component
class GeneradorTokenSeguro implements GeneradorToken {

    private static final int BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    @Override
    public TokenAcceso xerar() {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return TokenAcceso.de(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
