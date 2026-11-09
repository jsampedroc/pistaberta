package org.pistaberta.infrastructure.web;

import org.pistaberta.domain.reserva.FranxaInvalida;
import org.pistaberta.domain.reserva.PistaNonExiste;
import org.pistaberta.domain.reserva.ReservaNonExiste;
import org.pistaberta.domain.reserva.ReservaXaCancelada;
import org.pistaberta.domain.reserva.SlotNonDisponible;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Errores como {@code application/problem+json} (RFC 7807). Los textos son fijos: nunca SQL, trazas ni ids ajenos.
 * Los errores de MVC (JSON mal formado, UUID inválido…) los traduce la clase base a 400.
 */
@RestControllerAdvice
class ProblemasHttp extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemasHttp.class);

    @ExceptionHandler(SlotNonDisponible.class)
    ProblemDetail slotNonDisponible() {
        return problema(HttpStatus.CONFLICT, "Franxa non dispoñible", "La franxa solicitada ya está reservada.");
    }

    @ExceptionHandler(PistaNonExiste.class)
    ProblemDetail pistaNonExiste() {
        return problema(HttpStatus.NOT_FOUND, "Pista non existe", "La pista no existe.");
    }

    @ExceptionHandler(ReservaNonExiste.class)
    ProblemDetail reservaNonExiste() {
        return problema(HttpStatus.NOT_FOUND, "Reserva non existe", "La reserva no existe.");
    }

    @ExceptionHandler(ReservaXaCancelada.class)
    ProblemDetail reservaXaCancelada() {
        return problema(HttpStatus.CONFLICT, "Reserva xa cancelada", "La reserva ya está cancelada.");
    }

    @ExceptionHandler({FranxaInvalida.class, SolicitudInvalida.class})
    ProblemDetail solicitudeInvalida() {
        return problema(HttpStatus.BAD_REQUEST, "Solicitude inválida", "La solicitud no cumple el contrato.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail inesperado(Exception e) {
        LOG.error("Error no previsto en la API", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Error interno.");
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        return problema;
    }
}
