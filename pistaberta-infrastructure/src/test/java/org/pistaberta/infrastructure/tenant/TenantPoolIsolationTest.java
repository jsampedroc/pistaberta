package org.pistaberta.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.domain.tenant.TenantId;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** R3 · pool: el GUC de tenant es local a la transacción y no se filtra a la siguiente de la misma conexión. */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
@ActiveProfiles("test")
class TenantPoolIsolationTest {

    private static final UUID A = TestPostgres.A_ESTRADA;
    private static final String GUC = "select pg_backend_pid() as pid, current_setting('app.tenant_id', true) as tenant";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ThreadLocalTenantContext context;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        context.clear();
    }

    @Test
    void tenantDoesNotLeakToTheNextTransactionOnTheSameConnection() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        context.set(new TenantId(A));
        Map<String, Object> first = tx.execute(status -> jdbc.queryForMap(GUC));
        context.clear();
        Map<String, Object> second = tx.execute(status -> jdbc.queryForMap(GUC));

        assertThat(first.get("tenant")).isEqualTo(A.toString());
        // Medición: el pool de tamaño 1 reutiliza la misma conexión física, si no la prueba no demuestra nada.
        assertThat(second.get("pid")).as("misma conexión física").isEqualTo(first.get("pid"));
        assertThat(second.get("tenant")).as("tenant de la tx anterior").isIn(null, "");
    }

    @Test
    void tenantIsNotSetOutsideATransaction() {
        context.set(new TenantId(A));
        String tenant = jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class);
        assertThat(tenant).isIn(null, "");
    }
}
