package org.pistaberta.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** R5 · perfil por defecto (como prod): sin semilla de concellos y sin resolver de tenant (falla cerrado). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DefaultProfileTest {

    @Container
    static final PostgreSQLContainer<?> DB = TestPostgres.newContainer();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, DB);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void tenantCatalogIsEmptyWithoutDevOrTestProfile() {
        assertThat(jdbc.queryForObject("select count(*) from tenant", Long.class)).isZero();
    }

    @Test
    void apiFailsClosedWithoutTenantResolver() throws Exception {
        mvc.perform(get("/api/anything").header("X-Tenant-Id", "a-estrada")).andExpect(status().isUnauthorized());
    }

    @Test
    void reservationRoutesFailClosedWithoutTenantResolver() throws Exception {
        // D-012/D-014: sin spec de auth, en prod no hay resolver: reservar y cancelar no pueden abrirse por cabecera.
        mvc.perform(post("/api/pistas/00000000-0000-4000-8000-0000000000a1/reservas").header("X-Tenant-Id", "a-estrada")
                .contentType("application/json")
                .content("{\"inicio\":\"2026-11-20T18:00:00+01:00\",\"fin\":\"2026-11-20T19:00:00+01:00\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reservas/00000000-0000-4000-8000-0000000000a1/cancelar").header("X-Tenant-Id", "a-estrada"))
                .andExpect(status().isUnauthorized());
    }
}
