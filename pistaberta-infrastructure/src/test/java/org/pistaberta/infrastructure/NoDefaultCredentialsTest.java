package org.pistaberta.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** F5: application.yml (la config de prod) no trae contraseñas por defecto: sin variables, la app no arranca. */
class NoDefaultCredentialsTest {

    @Test
    void passwordsHaveNoDefaultValueOutsideTheDevProfile() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        PropertySource<?> yml = sources.get(0);

        assertThat(yml.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThat(yml.getProperty("spring.flyway.password")).isEqualTo("${DB_MIGRATION_PASSWORD}");
        assertThat(yml.getProperty("spring.flyway.placeholders.app_db_password")).isEqualTo("${DB_PASSWORD}");
    }
}
