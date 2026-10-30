package org.pistaberta.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantIdTest {

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new TenantId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parsesAndPrintsTheUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(TenantId.of(uuid.toString()).value()).isEqualTo(uuid);
        assertThat(new TenantId(uuid)).hasToString(uuid.toString());
    }
}
