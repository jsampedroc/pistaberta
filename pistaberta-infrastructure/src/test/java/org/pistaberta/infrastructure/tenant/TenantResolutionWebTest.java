package org.pistaberta.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** C7 · resolución del tenant por header (dev/test) y de punta a punta con la app conectada como pistaberta_app. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantResolutionWebTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ThreadLocalTenantContext context;

    @BeforeEach
    void rows() throws SQLException {
        TestPostgres.resetProbe(TestPostgres.SHARED, 2, 1);
    }

    @Test
    void missingHeaderIsBadRequest() throws Exception {
        mvc.perform(get("/api/test/tenant")).andExpect(status().isBadRequest());
    }

    @Test
    void malformedSlugIsBadRequest() throws Exception {
        mvc.perform(get("/api/test/tenant").header("X-Tenant-Id", "A Estrada;--")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownTenantIsNotFound() throws Exception {
        mvc.perform(get("/api/test/tenant").header("X-Tenant-Id", "non-existent")).andExpect(status().isNotFound());
    }

    @Test
    void knownTenantIsResolvedAndContextIsClearedAfterTheRequest() throws Exception {
        mvc.perform(get("/api/test/tenant").header("X-Tenant-Id", "a-estrada"))
                .andExpect(status().isOk())
                .andExpect(content().string(TestPostgres.A_ESTRADA.toString()));
        assertThat(context.current()).as("contexto limpio tras la request").isEmpty();
    }

    @Test
    void endToEndEachTenantOnlySeesItsOwnRows() throws Exception {
        mvc.perform(get("/api/test/probe").header("X-Tenant-Id", "a-estrada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("a1"))
                .andExpect(jsonPath("$[1]").value("a2"));
        mvc.perform(get("/api/test/probe").header("X-Tenant-Id", "lalin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("b1"));
    }
}
