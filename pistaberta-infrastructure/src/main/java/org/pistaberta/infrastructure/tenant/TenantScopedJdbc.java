package org.pistaberta.infrastructure.tenant;

import java.util.List;
import java.util.Map;
import org.pistaberta.application.tenant.TenantContext;
import org.pistaberta.domain.tenant.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Único camino de acceso a datos con tenant: el filtro por tenant es obligatorio y lo pone este componente,
 * no cada repositorio. Sin tenant en contexto lanza TenantNotSetException. Es la capa aplicativa de D-003;
 * la capa de BD es la policy RLS.
 */
@Component
public class TenantScopedJdbc {

    static final String TENANT_PARAM = "tenantId";

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantContext tenantContext;

    public TenantScopedJdbc(NamedParameterJdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /** La consulta debe devolver la columna tenant_id; solo se devuelven filas del tenant en contexto. */
    public <T> List<T> query(String sql, Map<String, ?> params, RowMapper<T> mapper) {
        MapSqlParameterSource bound = bind(params);
        return jdbc.query("SELECT * FROM (" + sql + ") AS scoped WHERE scoped.tenant_id = :tenantId", bound, mapper);
    }

    /** INSERT/UPDATE/DELETE: el SQL debe usar :tenantId (se rechaza si no lo menciona). */
    public int update(String sql, Map<String, ?> params) {
        if (!sql.contains(":" + TENANT_PARAM)) {
            throw new IllegalArgumentException("El SQL de escritura debe filtrar o asignar por :" + TENANT_PARAM);
        }
        return jdbc.update(sql, bind(params));
    }

    private MapSqlParameterSource bind(Map<String, ?> params) {
        TenantId tenant = tenantContext.require();
        if (params.containsKey(TENANT_PARAM)) {
            throw new IllegalArgumentException("El parámetro :" + TENANT_PARAM + " está reservado");
        }
        return new MapSqlParameterSource(params).addValue(TENANT_PARAM, tenant.value());
    }
}
