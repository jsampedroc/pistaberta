package org.pistaberta.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pistaberta.application.tenant.TenantNotSetException;
import org.pistaberta.domain.tenant.TenantId;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * R2 · filtro aplicativo. Se ejecuta sobre una conexión superuser (que bypassa RLS) para que solo cuente el
 * filtro del código. Además: fail-closed en código sin tenant (C4).
 */
@SpringBootTest
@ActiveProfiles("test")
class TenantScopedJdbcTest {

    private static final UUID A = TestPostgres.A_ESTRADA;
    private static final String SELECT_ALL = "select id, tenant_id, etiqueta from tenant_probe";
    private static final RowMapper<UUID> TENANT_OF_ROW = (rs, i) -> rs.getObject("tenant_id", UUID.class);

    private final ThreadLocalTenantContext context = new ThreadLocalTenantContext();
    private TenantScopedJdbc jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @BeforeEach
    void rows() throws SQLException {
        TestPostgres.resetProbe(TestPostgres.SHARED, 2, 2);
        var db = TestPostgres.SHARED;
        var bypassRls = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        jdbc = new TenantScopedJdbc(new NamedParameterJdbcTemplate(bypassRls), context);
    }

    @AfterEach
    void clear() {
        context.clear();
    }

    @Test
    void queryOnlyReturnsCurrentTenantRows() {
        context.set(new TenantId(A));
        List<UUID> tenants = jdbc.query(SELECT_ALL, Map.of(), TENANT_OF_ROW);
        assertThat(tenants).hasSize(2).containsOnly(A);
    }

    @Test
    void withoutTenantAccessFailsClosed() {
        assertThatThrownBy(() -> jdbc.query(SELECT_ALL, Map.of(), TENANT_OF_ROW)).isInstanceOf(TenantNotSetException.class);
        assertThatThrownBy(() -> jdbc.update("delete from tenant_probe where tenant_id = :tenantId", Map.of()))
                .isInstanceOf(TenantNotSetException.class);
    }

    @Test
    void writeWithoutTenantPlaceholderIsRejected() {
        context.set(new TenantId(A));
        assertThatThrownBy(() -> jdbc.update("delete from tenant_probe", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateIsScopedToTheCurrentTenant() {
        context.set(new TenantId(A));
        int updated = jdbc.update("update tenant_probe set etiqueta = 'x' where tenant_id = :tenantId", Map.of());
        assertThat(updated).isEqualTo(2);
    }
}
