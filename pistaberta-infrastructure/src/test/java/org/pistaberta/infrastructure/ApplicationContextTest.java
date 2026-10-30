package org.pistaberta.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Falla si el contexto no carga o si Flyway no aplica V1 sobre una BD PostGIS real.
 * Solo se inyectan las credenciales del contenedor; la config de Flyway es la de application.yml.
 */
@SpringBootTest
@Testcontainers
class ApplicationContextTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedV1() {
        String version = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success AND version = '1'", String.class);
        assertThat(version).isEqualTo("1");
    }

    @Test
    void postgisExtensionIsInstalled() {
        String postgis = jdbc.queryForObject("SELECT PostGIS_Version()", String.class);
        assertThat(postgis).isNotBlank();
    }

    @Test
    void versionTableIsSeeded() {
        String version = jdbc.queryForObject(
                "SELECT version FROM app_version WHERE component = 'schema'", String.class);
        assertThat(version).isEqualTo("0.1.0");
    }
}
