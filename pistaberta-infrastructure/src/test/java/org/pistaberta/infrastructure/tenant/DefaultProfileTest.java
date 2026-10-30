package org.pistaberta.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
