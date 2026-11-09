package org.pistaberta.infrastructure.web;

/** La petición HTTP no cumple el contrato (campos ausentes). Se traduce a 400. */
class SolicitudInvalida extends RuntimeException {

    SolicitudInvalida(String mensaje) {
        super(mensaje);
    }
}
