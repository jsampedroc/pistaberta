package org.pistaberta.infrastructure.acceso;

import java.util.Map;
import org.pistaberta.application.acceso.RepositorioAcceso;
import org.pistaberta.domain.acceso.TokenAcceso;
import org.pistaberta.domain.reserva.ReservaId;
import org.pistaberta.infrastructure.tenant.TenantScopedJdbc;
import org.springframework.stereotype.Component;

/** Guarda solo el SHA-256 del token, dentro de la transacción y el tenant actuales (RLS + filtro aplicativo). */
@Component
class JdbcRepositorioAcceso implements RepositorioAcceso {

    private final TenantScopedJdbc jdbc;

    JdbcRepositorioAcceso(TenantScopedJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void gardar(ReservaId reserva, TokenAcceso token) {
        jdbc.insert("reserva_acceso", Map.of("reserva_id", reserva.value(), "token_hash", token.hash()));
    }
}
