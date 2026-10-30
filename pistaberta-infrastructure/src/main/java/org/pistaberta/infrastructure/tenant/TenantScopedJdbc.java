package org.pistaberta.infrastructure.tenant;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.pistaberta.application.tenant.TenantContext;
import org.pistaberta.domain.tenant.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Único camino de acceso a datos con tenant: el filtro por tenant lo pone este componente, no cada repositorio.
 * Lecturas: la consulta se envuelve y se filtra por tenant_id. Escrituras: INSERT asigna el tenant, UPDATE y DELETE
 * añaden {@code tenant_id = :tenantId}, y tenant_id no se puede reasignar. Sin tenant en contexto lanza
 * TenantNotSetException. Es la capa aplicativa de D-003; la capa de BD es la policy RLS.
 *
 * <p>Los fragmentos SQL ({@code sql}, {@code where}) deben ser constantes del código y los valores van siempre como
 * parámetros: RLS protege frente a filtros olvidados, no frente a inyección SQL (el rol de la app puede fijar el GUC).
 */
@Component
public class TenantScopedJdbc {

    static final String TENANT_PARAM = "tenantId";
    private static final String TENANT_COLUMN = "tenant_id";
    private static final String SET_PREFIX = "set_";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

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

    /** INSERT con tenant_id = tenant en contexto; no se admite tenant_id entre las columnas. */
    public int insert(String table, Map<String, ?> columns) {
        requireColumns(columns, "columns");
        MapSqlParameterSource bound = bind(columns);
        String names = columns.keySet().stream().map(TenantScopedJdbc::identifier).collect(Collectors.joining(", "));
        String values = columns.keySet().stream().map(c -> ":" + c).collect(Collectors.joining(", "));
        return jdbc.update("INSERT INTO " + identifier(table) + " (tenant_id, " + names + ") VALUES (:tenantId, " + values + ")",
                bound);
    }

    /** UPDATE solo sobre filas del tenant en contexto que cumplan {@code where}; tenant_id no se puede reasignar. */
    public int update(String table, Map<String, ?> set, String where, Map<String, ?> params) {
        requireColumns(set, "set");
        requireWhere(where, params);
        MapSqlParameterSource bound = bind(params);
        String assignments = set.keySet().stream()
                .map(c -> identifier(c) + " = :" + SET_PREFIX + c)
                .collect(Collectors.joining(", "));
        set.forEach((column, value) -> bound.addValue(SET_PREFIX + column, value));
        return jdbc.update("UPDATE " + identifier(table) + " SET " + assignments
                + " WHERE tenant_id = :tenantId AND (" + where + ")", bound);
    }

    /** DELETE solo sobre filas del tenant en contexto que cumplan {@code where}. */
    public int delete(String table, String where, Map<String, ?> params) {
        requireWhere(where, params);
        return jdbc.update("DELETE FROM " + identifier(table) + " WHERE tenant_id = :tenantId AND (" + where + ")",
                bind(params));
    }

    private MapSqlParameterSource bind(Map<String, ?> params) {
        TenantId tenant = tenantContext.require();
        if (params.containsKey(TENANT_PARAM)) {
            throw new IllegalArgumentException("El parámetro :" + TENANT_PARAM + " está reservado");
        }
        return new MapSqlParameterSource(params).addValue(TENANT_PARAM, tenant.value());
    }

    private static void requireColumns(Map<String, ?> columns, String what) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(what + " no puede estar vacío");
        }
        if (columns.containsKey(TENANT_COLUMN)) {
            throw new IllegalArgumentException("tenant_id lo asigna el contexto: no se puede indicar ni reasignar");
        }
    }

    private static void requireWhere(String where, Map<String, ?> params) {
        if (where == null || where.isBlank()) {
            throw new IllegalArgumentException("where es obligatorio (usa una condición explícita)");
        }
        if (params.keySet().stream().anyMatch(k -> k.startsWith(SET_PREFIX))) {
            throw new IllegalArgumentException("Los parámetros que empiezan por " + SET_PREFIX + " están reservados");
        }
    }

    private static String identifier(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Identificador SQL no válido: " + name);
        }
        return name;
    }
}
