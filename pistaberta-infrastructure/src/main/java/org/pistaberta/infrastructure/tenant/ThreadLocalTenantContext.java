package org.pistaberta.infrastructure.tenant;

import java.util.Optional;
import org.pistaberta.application.tenant.TenantContext;
import org.pistaberta.domain.tenant.TenantId;
import org.springframework.stereotype.Component;

/** Tenant de la request en curso, ligado al hilo (async e hilos virtuales fuera de alcance: D-006). */
@Component
public class ThreadLocalTenantContext implements TenantContext {

    private final ThreadLocal<TenantId> current = new ThreadLocal<>();

    @Override
    public Optional<TenantId> current() {
        return Optional.ofNullable(current.get());
    }

    public void set(TenantId tenant) {
        current.set(tenant);
    }

    public void clear() {
        current.remove();
    }
}
