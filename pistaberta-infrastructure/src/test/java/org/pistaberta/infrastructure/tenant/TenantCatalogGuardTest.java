package org.pistaberta.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pistaberta.infrastructure.TestPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** R4 · guard: toda tabla con columna tenant_id tiene RLS enabled + FORCE + al menos una policy. */
@SpringBootTest
@ActiveProfiles("test")
class TenantCatalogGuardTest {

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
                               (select count(*) from pg_policy p where p.polrelid = cls.oid) as policies
                        from pg_class cls
                        join pg_attribute a on a.attrelid = cls.oid and a.attname = 'tenant_id' and not a.attisdropped
                        where cls.relkind in ('r', 'p') and cls.relnamespace = 'public'::regnamespace
                        """)) {
            while (rs.next()) {
                String table = rs.getString("relname");
                checked.add(table);
                if (!rs.getBoolean("relrowsecurity") || !rs.getBoolean("relforcerowsecurity") || rs.getInt("policies") < 1) {
                    violations.add(table);
                }
            }
        }
        // Anti verde mudo: si no se encuentra ninguna tabla con tenant_id, el guard no está comprobando nada.
        assertThat(checked).as("tablas con tenant_id").contains("tenant_probe");
        assertThat(violations).as("tablas con tenant_id sin RLS + FORCE + policy").isEmpty();
    }
}
