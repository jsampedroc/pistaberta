package org.pistaberta.infrastructure.reserva;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.application.acceso.GeneradorToken;
import org.pistaberta.application.acceso.ReservaConAcceso;
import org.pistaberta.application.acceso.ReservarPistaConAcceso;
import org.pistaberta.domain.acceso.TokenAcceso;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.tenant.TenantId;
import org.pistaberta.infrastructure.TestPostgres;
import org.pistaberta.infrastructure.tenant.ThreadLocalTenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** EX0: el token se guarda solo como hash y reserva + acceso se confirman o se deshacen juntos. */
@SpringBootTest
@ActiveProfiles("test")
class ReservarPistaConAccesoIntegracionTest {

    private static final UUID PISTA_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final Instant T = Instant.parse("2026-11-02T10:00:00Z");
    /** Token fijo para forzar la colisión del hash (único por tenant) en la segunda reserva. */
    private static final String TOKEN_FIJO = "F".repeat(43);

    @TestConfiguration
    static class GeneradorFijo {
        @Bean
        @Primary
        GeneradorToken generadorFijo() {
            return () -> TokenAcceso.de(TOKEN_FIJO);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @Autowired
    ReservarPistaConAcceso reservar;

    @Autowired
    ThreadLocalTenantContext context;

    @BeforeEach
    void preparar() throws Exception {
        TestPostgres.resetReservas(TestPostgres.SHARED);
        TestPostgres.insertPista(TestPostgres.SHARED, TestPostgres.A_ESTRADA, PISTA_A, "Pista A");
        context.set(new TenantId(TestPostgres.A_ESTRADA));
    }

    @AfterEach
    void limpiar() {
        context.clear();
    }

    private static Franxa franxa(long inicioMin, long finMin) {
        return new Franxa(T.plusSeconds(inicioMin * 60), T.plusSeconds(finMin * 60));
    }

    private static long filas(String tabla) throws Exception {
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED)) {
            return TestPostgres.count(c, "select count(*) from " + tabla);
        }
    }

    @Test
    void storesTheSha256OfTheTokenAndNeverTheTokenItself() throws Exception {
        ReservaConAcceso r = reservar.executar(new PistaId(PISTA_A), franxa(0, 60));
        assertThat(r.token().valor()).isEqualTo(TOKEN_FIJO);
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED);
                PreparedStatement ps = c.prepareStatement(
                        "select token_hash = sha256(convert_to(?, 'UTF8')) as es_el_hash, "
                                + "token_hash = convert_to(?, 'UTF8') as es_el_token from reserva_acceso where reserva_id = ?")) {
            // sha256() lo calcula Postgres: oráculo independiente de la implementación Java.
            ps.setString(1, TOKEN_FIJO);
            ps.setString(2, TOKEN_FIJO);
            ps.setObject(3, r.reserva().id().value());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("es_el_hash")).isTrue();
                assertThat(rs.getBoolean("es_el_token")).isFalse();
            }
        }
    }

    @Test
    void aFailureStoringTheAccessRollsBackTheReservation() throws Exception {
        reservar.executar(new PistaId(PISTA_A), franxa(0, 60));
        // La 2ª reserva es válida (otra franxa) pero su token colisiona: el insert del acceso falla (23505).
        assertThatThrownBy(() -> reservar.executar(new PistaId(PISTA_A), franxa(120, 180)))
                .isInstanceOf(DataAccessException.class);
        assertThat(filas("reserva")).as("la reserva de la 2ª llamada no debe quedar huérfana").isEqualTo(1);
        assertThat(filas("reserva_acceso")).isEqualTo(1);
    }
}
