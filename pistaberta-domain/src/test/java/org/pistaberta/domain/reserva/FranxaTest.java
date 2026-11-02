package org.pistaberta.domain.reserva;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FranxaTest {

    private static final Instant T = Instant.parse("2026-11-02T10:00:00Z");

    private static Franxa franxa(Instant inicio, Instant fin) {
        return new Franxa(inicio, fin);
    }

    /** RV3a: fin == inicio ajeno NO solapa (intervalo semiabierto). */
    @Test
    void adjacentFranxasDoNotOverlap() {
        Franxa a = franxa(T, T.plus(Duration.ofHours(1)));
        Franxa b = franxa(T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)));
        assertThat(a.solapaCon(b)).isFalse();
        assertThat(b.solapaCon(a)).isFalse();
    }

    @Test
    void overlappingFranxasOverlapInBothDirections() {
        Franxa a = franxa(T, T.plus(Duration.ofHours(1)));
        Franxa b = franxa(T.plus(Duration.ofMinutes(30)), T.plus(Duration.ofMinutes(90)));
        assertThat(a.solapaCon(b)).isTrue();
        assertThat(b.solapaCon(a)).isTrue();
    }

    @Test
    void containedFranxaOverlaps() {
        Franxa grande = franxa(T, T.plus(Duration.ofHours(3)));
        Franxa pequena = franxa(T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)));
        assertThat(grande.solapaCon(pequena)).isTrue();
        assertThat(pequena.solapaCon(grande)).isTrue();
    }

    /** RV9: fin <= inicio se rechaza. */
    @Test
    void emptyOrReversedFranxaIsRejected() {
        assertThatThrownBy(() -> franxa(T, T)).isInstanceOf(FranxaInvalida.class);
        assertThatThrownBy(() -> franxa(T.plusSeconds(1), T)).isInstanceOf(FranxaInvalida.class);
    }

    /** RV9: se normaliza a microsegundos, como Postgres. */
    @Test
    void instantsAreNormalizedToMicroseconds() {
        Franxa f = franxa(T.plusNanos(999), T.plus(Duration.ofHours(1)).plusNanos(999));
        assertThat(f.inicio()).isEqualTo(T);
        assertThat(f.fin()).isEqualTo(T.plus(Duration.ofHours(1)));
    }

    /** RV9: sin normalizar, 500 ns de más en el fin harían solapar dos franxas que en la BD son adyacentes. */
    @Test
    void nanosecondNoiseCannotCreateAFalseOverlap() {
        Franxa a = franxa(T, T.plus(Duration.ofHours(1)).plusNanos(500));
        Franxa b = franxa(T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)));
        assertThat(a.solapaCon(b)).isFalse();
    }
}
