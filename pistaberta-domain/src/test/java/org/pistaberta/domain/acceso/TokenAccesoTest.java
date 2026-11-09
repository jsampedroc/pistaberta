package org.pistaberta.domain.acceso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TokenAccesoTest {

    private static final String VALIDO = "A".repeat(43);

    @Test
    void hashIsTheSha256OfTheValue() {
        // Oráculo independiente de la implementación: printf 'A'x43 | shasum -a 256
        assertThat(HexFormat.of().formatHex(TokenAcceso.de(VALIDO).hash()))
                .isEqualTo("0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a");
    }

    @Test
    void toStringNeverRevealsTheValue() {
        assertThat(TokenAcceso.de(VALIDO).toString()).doesNotContain("AAAA").isEqualTo("TokenAcceso[***]");
    }

    @Test
    void matchesItsOwnHashAndNotAnotherOne() {
        TokenAcceso token = TokenAcceso.de(VALIDO);
        assertThat(token.coincideCon(token.hash())).isTrue();
        assertThat(token.coincideCon(TokenAcceso.de("B".repeat(43)).hash())).isFalse();
    }

    @Test
    void rejectsTokensWithAnInvalidFormat() {
        for (String malo : new String[] {"", "corto", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "=", "A".repeat(42) + "+",
                "A".repeat(42) + " "}) {
            assertThatThrownBy(() -> TokenAcceso.de(malo)).as("'%s'", malo).isInstanceOf(TokenAccesoInvalido.class);
        }
    }
}
