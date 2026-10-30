package org.pistaberta.application.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pistaberta.domain.tenant.TenantId;

class TenantContextTest {

    @Test
    void requireFailsClosedWithoutTenant() {
        TenantContext empty = Optional::empty;
        assertThatThrownBy(empty::require).isInstanceOf(TenantNotSetException.class);
    }

    @Test
    void requireReturnsTheCurrentTenant() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        TenantContext ctx = () -> Optional.of(tenant);
        assertThat(ctx.require()).isEqualTo(tenant);
    }
}
