package org.pistaberta.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * R4 · guard: TODA tabla de public es un catálogo global conocido o lleva tenant_id con RLS enabled + FORCE + policy.
 * Revisa todas las tablas (no solo las que se llaman tenant_id) para que una tabla sin RLS no pase desapercibida.
 */
@SpringBootTest
@ActiveProfiles("test")
class TenantCatalogGuardTest {

    /**
     * Tablas globales sin datos de concello (spatial_ref_sys es el catálogo de SRIDs de PostGIS).
     * Añadir una exige tocar este test y pasar revisión.
     */
    private static final Set<String> GLOBAL_CATALOGS =
            Set.of("tenant", "app_version", "flyway_schema_history", "spatial_ref_sys");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, TestPostgres.SHARED);
    }

    @Test
    void everyTenantTableEnforcesRowLevelSecurity() throws SQLException {
        List<String> checked = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        try (Connection c = TestPostgres.superuser(TestPostgres.SHARED);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("""
                        select cls.relname, cls.relrowsecurity, cls.relforcerowsecurity,
                               (select count(*) from pg_policy p where p.polrelid = cls.oid) as policies,
                               exists (select 1 from pg_attribute a
                                       where a.attrelid = cls.oid and a.attname = 'tenant_id' and not a.attisdropped) as has_tenant
                        from pg_class cls
                        where cls.relkind in ('r', 'p') and cls.relnamespace = 'public'::regnamespace
                        """)) {
            while (rs.next()) {
                String table = rs.getString("relname");
                if (GLOBAL_CATALOGS.contains(table)) {
                    continue;
                }
                checked.add(table);
                boolean ok = rs.getBoolean("has_tenant") && rs.getBoolean("relrowsecurity")
                        && rs.getBoolean("relforcerowsecurity") && rs.getInt("policies") >= 1;
                if (!ok) {
                    violations.add(table);
                }
            }
        }
        // Anti verde mudo: si no se encuentra la tabla de prueba, el guard no está comprobando nada.
        assertThat(checked).as("tablas de datos").contains("tenant_probe");
        assertThat(violations).as("tablas sin tenant_id + RLS + FORCE + policy").isEmpty();
    }
}
