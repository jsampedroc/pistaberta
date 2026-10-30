package org.pistaberta.infrastructure.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.pistaberta.application.tenant.TenantContext;
import org.pistaberta.domain.tenant.TenantId;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fija el tenant en la BD al empezar cada transacción con set_config(..., true): el valor es LOCAL a la
 * transacción, así que al hacer commit/rollback desaparece y una conexión devuelta al pool nunca arrastra tenant.
 * Sin tenant en contexto no se fija nada y la policy RLS no deja ver ninguna fila (fail-closed).
 */
public class TenantAwareTransactionManager extends DataSourceTransactionManager {

    private final TenantContext tenantContext;

    public TenantAwareTransactionManager(DataSource dataSource, TenantContext tenantContext) {
        super(dataSource);
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        tenantContext.current().ifPresent(tenant -> applyTenant(transaction, tenant));
    }

    private void applyTenant(Object transaction, TenantId tenant) {
        ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
        Connection connection = holder.getConnection();
        try (PreparedStatement ps = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        } catch (SQLException e) {
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException("No se pudo fijar el tenant en la transacción", e);
        }
    }
}
