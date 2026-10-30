package org.pistaberta.infrastructure.tenant;

import java.util.Optional;
import java.util.UUID;
import org.pistaberta.domain.tenant.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Catálogo global de concellos (tabla tenant, sin RLS, solo lectura para la app). */
@Component
public class TenantCatalog {

    private final JdbcTemplate jdbc;

    public TenantCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantId> findBySlug(String slug) {
        return jdbc.query("select id from tenant where slug = ?", (rs, i) -> rs.getObject("id", UUID.class), slug)
                .stream()
                .findFirst()
                .map(TenantId::new);
    }
}
