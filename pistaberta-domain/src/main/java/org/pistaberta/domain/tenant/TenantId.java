package org.pistaberta.domain.tenant;

import java.util.Objects;
import java.util.UUID;

/** Identificador de un concello (tenant). */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId of(String raw) {
        return new TenantId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
