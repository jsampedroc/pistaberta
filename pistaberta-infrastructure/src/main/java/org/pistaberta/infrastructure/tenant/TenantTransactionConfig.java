package org.pistaberta.infrastructure.tenant;

import javax.sql.DataSource;
import org.pistaberta.application.tenant.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class TenantTransactionConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource, TenantContext tenantContext) {
        return new TenantAwareTransactionManager(dataSource, tenantContext);
    }
}
