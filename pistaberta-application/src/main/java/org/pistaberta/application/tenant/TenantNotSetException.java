package org.pistaberta.application.tenant;

/** Se intentó acceder a datos sin tenant en contexto: el acceso falla cerrado. */
public class TenantNotSetException extends RuntimeException {

    public TenantNotSetException() {
        super("No hay tenant en el contexto: acceso a datos denegado");
    }
}
