package org.pistaberta.infrastructure.reserva;

import java.util.function.Supplier;
import org.pistaberta.application.reserva.Transaccion;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transacción sobre el TenantAwareTransactionManager: al empezar fija el tenant en la BD (GUC local). */
@Component
class TransaccionSpring implements Transaccion {

    private final TransactionTemplate template;

    TransaccionSpring(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T executar(Supplier<T> traballo) {
        return template.execute(status -> traballo.get());
    }
}
