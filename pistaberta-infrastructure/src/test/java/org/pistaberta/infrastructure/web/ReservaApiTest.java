package org.pistaberta.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** D-012: API REST mínima (contrato de SDD-extras §5). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservaApiTest {

    private static final UUID PISTA_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID PISTA_B = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final String TENANT = "a-estrada";
    private static final String UNA_A_DOUS = "{\"inicio\":\"2026-11-20T18:00:00+01:00\",\"fin\":\"2026-11-20T19:00:00+01:00\"}";
    private static final String SOLAPADA = "{\"inicio\":\"2026-11-20T18:30:00+01:00\",\"fin\":\"2026-11-20T19:30:00+01:00\"}";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void preparar() throws Exception {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        TestPostgres.insertPista(TestPostgres.SHARED, TestPostgres.A_ESTRADA, PISTA_A, "Pista A");
        TestPostgres.insertPista(TestPostgres.SHARED, TestPostgres.LALIN, PISTA_B, "Pista B");
    }

    private MockHttpServletRequestBuilder reservar(UUID pista, String cuerpo) {
        return post("/api/pistas/{id}/reservas", pista).header("X-Tenant-Id", TENANT).contentType(APPLICATION_JSON).content(cuerpo);
    }

    private MockHttpServletRequestBuilder cancelar(String id) {
        return post("/api/reservas/{id}/cancelar", id).header("X-Tenant-Id", TENANT);
    }

    @Test
    void reservingReturns201WithTheTokenOnceAndNoStoreAndStoresOnlyItsHash() throws Exception {
        MvcResult r = mvc.perform(reservar(PISTA_A, UNA_A_DOUS))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.estado").value("confirmada"))
                .andExpect(jsonPath("$.reservaId").exists())
                .andExpect(jsonPath("$.token").isString())
                .andReturn();
        String cuerpo = r.getResponse().getContentAsString();
        String token = JsonPath.read(cuerpo, "$.token");
        String reservaId = JsonPath.read(cuerpo, "$.reservaId");
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED);
                PreparedStatement ps = c.prepareStatement(
                        "select token_hash = sha256(convert_to(?, 'UTF8')) from reserva_acceso where reserva_id = ?::uuid")) {
            ps.setString(1, token);
            ps.setString(2, reservaId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("en BD solo el SHA-256 del token devuelto").isTrue();
            }
        }
    }

    @Test
    void twoReservationsGetDifferentTokens() throws Exception {
        String a = JsonPath.read(mvc.perform(reservar(PISTA_A, UNA_A_DOUS)).andReturn().getResponse().getContentAsString(), "$.token");
        String b = JsonPath.read(mvc.perform(reservar(PISTA_A,
                "{\"inicio\":\"2026-11-21T18:00:00+01:00\",\"fin\":\"2026-11-21T19:00:00+01:00\"}"))
                .andReturn().getResponse().getContentAsString(), "$.token");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void overlappingReservationIs409ProblemJson() throws Exception {
        mvc.perform(reservar(PISTA_A, UNA_A_DOUS)).andExpect(status().isCreated());
        String cuerpo = mvc.perform(reservar(PISTA_A, SOLAPADA))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.status").value(409))
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).as("sin SQL, trazas ni nombres de clase").doesNotContainIgnoringCase("sql")
                .doesNotContain("Exception").doesNotContain("org.pistaberta").doesNotContain("reserva_sen_solape");
    }

    @Test
    void aPistaOfAnotherTenantIs404() throws Exception {
        mvc.perform(reservar(PISTA_B, UNA_A_DOUS)).andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")));
    }

    @Test
    void invalidRequestsAre400() throws Exception {
        mvc.perform(reservar(PISTA_A, "{\"inicio\":\"2026-11-20T19:00:00+01:00\",\"fin\":\"2026-11-20T18:00:00+01:00\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(reservar(PISTA_A, "{\"inicio\":\"2026-11-20T18:00:00+01:00\"}")).andExpect(status().isBadRequest());
        mvc.perform(reservar(PISTA_A, "{no es json")).andExpect(status().isBadRequest());
        mvc.perform(reservar(PISTA_A, "{\"inicio\":\"ayer\",\"fin\":\"mañana\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/pistas/no-es-un-uuid/reservas").header("X-Tenant-Id", TENANT).contentType(APPLICATION_JSON)
                .content(UNA_A_DOUS)).andExpect(status().isBadRequest());
    }

    @Test
    void cancellingIs204ThenAgainIs409AndUnknownIs404() throws Exception {
        String id = JsonPath.read(mvc.perform(reservar(PISTA_A, UNA_A_DOUS)).andReturn().getResponse().getContentAsString(), "$.reservaId");
        mvc.perform(cancelar(id)).andExpect(status().isNoContent());
        mvc.perform(cancelar(id)).andExpect(status().isConflict());
        mvc.perform(cancelar(UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test
    void cancellingFreesTheFranxa() throws Exception {
        String id = JsonPath.read(mvc.perform(reservar(PISTA_A, UNA_A_DOUS)).andReturn().getResponse().getContentAsString(), "$.reservaId");
        mvc.perform(cancelar(id)).andExpect(status().isNoContent());
        mvc.perform(reservar(PISTA_A, UNA_A_DOUS)).andExpect(status().isCreated());
    }

    @Test
    void anotherTenantCannotCancelAReservation() throws Exception {
        String id = JsonPath.read(mvc.perform(reservar(PISTA_A, UNA_A_DOUS)).andReturn().getResponse().getContentAsString(), "$.reservaId");
        mvc.perform(post("/api/reservas/{id}/cancelar", id).header("X-Tenant-Id", "lalin")).andExpect(status().isNotFound());
    }

    @Test
    void withoutATenantTheFilterRejectsTheRequest() throws Exception {
        mvc.perform(post("/api/pistas/{id}/reservas", PISTA_A).contentType(MediaType.APPLICATION_JSON).content(UNA_A_DOUS))
                .andExpect(status().isBadRequest());
    }
}
