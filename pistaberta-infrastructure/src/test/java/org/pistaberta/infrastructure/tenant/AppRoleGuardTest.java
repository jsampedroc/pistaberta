package org.pistaberta.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.PistaAbertaApplication;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** F3: la app se niega a arrancar conectada con un rol que se salta RLS (aquí, el superuser del contenedor). */
class AppRoleGuardTest {

    @Test
    void refusesToStartConnectedAsASuperuser() {
        var db = TestPostgres.SHARED;
        var app = new SpringApplicationBuilder(PistaAbertaApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE);

        assertThatThrownBy(() -> app.run(
                        "--spring.datasource.url=" + db.getJdbcUrl(),
                        "--spring.datasource.username=" + db.getUsername(),
                        "--spring.datasource.password=" + db.getPassword(),
                        "--spring.flyway.url=" + db.getJdbcUrl(),
                        "--spring.flyway.user=" + db.getUsername(),
                        "--spring.flyway.password=" + db.getPassword(),
                        "--spring.flyway.placeholders.app_db_password=" + TestPostgres.APP_PASSWORD))
                .hasStackTraceContaining("SUPERUSER o BYPASSRLS");
    }
}
