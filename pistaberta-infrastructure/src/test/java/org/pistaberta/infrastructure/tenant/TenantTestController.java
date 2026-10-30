package org.pistaberta.infrastructure.tenant;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Solo test: endpoints mínimos para ejercitar resolver, filtro, transacción y RLS de punta a punta. */
@RestController
@Profile("test")
class TenantTestController {

    private final ThreadLocalTenantContext context;
    private final TenantScopedJdbc jdbc;

    TenantTestController(ThreadLocalTenantContext context, TenantScopedJdbc jdbc) {
        this.context = context;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/test/tenant")
    String tenant() {
        return context.require().toString();
    }

    @GetMapping("/api/test/probe")
    @Transactional(readOnly = true)
    List<String> probe() {
        return jdbc.query("select tenant_id, etiqueta from tenant_probe order by etiqueta", Map.of(),
                (rs, i) -> rs.getString("etiqueta"));
    }
}
