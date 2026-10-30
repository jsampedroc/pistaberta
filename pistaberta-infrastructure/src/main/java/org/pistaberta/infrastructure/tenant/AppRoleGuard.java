package org.pistaberta.infrastructure.tenant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Arranque fail-fast: si la app se conecta con un rol SUPERUSER o BYPASSRLS, la capa RLS de D-003 estaría
 * desactivada en silencio (p.ej. rol pre-aprovisionado por ops con atributos de más). Se niega a arrancar.
 */
@Component
class AppRoleGuard implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    AppRoleGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Boolean privileged = jdbc.queryForObject(
                "select rolsuper or rolbypassrls from pg_roles where rolname = current_user", Boolean.class);
        if (!Boolean.FALSE.equals(privileged)) {
            throw new IllegalStateException(
                    "La app está conectada con un rol SUPERUSER o BYPASSRLS: se saltaría RLS (D-003). Usa pistaberta_app.");
        }
    }
}
