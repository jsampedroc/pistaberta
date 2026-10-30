package org.pistaberta.application.tenant;

import java.util.Optional;
import org.pistaberta.domain.tenant.TenantId;

/** Puerto: tenant (concello) de la operación en curso. */
public interface TenantContext {

    Optional<TenantId> current();

    default TenantId require() {
        return current().orElseThrow(TenantNotSetException::new);
    }
}
