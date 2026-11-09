package org.pistaberta.infrastructure.acceso;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pistaberta.domain.acceso.TokenAcceso;

class GeneradorTokenSeguroTest {

    @Test
    void generatesDistinctWellFormedTokensOf256Bits() {
        GeneradorTokenSeguro generador = new GeneradorTokenSeguro();
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            TokenAcceso token = generador.xerar();
            assertThat(token.valor()).hasSize(TokenAcceso.LONGITUD).matches("[A-Za-z0-9_-]{43}");
            assertThat(token.hash()).hasSize(32);
            assertThat(vistos.add(token.valor())).as("token repetido en la iteración %d", i).isTrue();
        }
    }
}
