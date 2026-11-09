package org.pistaberta.domain.acceso;

/** El texto recibido no tiene el formato de un token de acceso. */
public class TokenAccesoInvalido extends RuntimeException {

    public TokenAccesoInvalido(String mensaje) {
        super(mensaje);
    }
}
