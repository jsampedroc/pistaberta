package org.pistaberta.infrastructure.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.pistaberta.application.acceso.ReservaConAcceso;
import org.pistaberta.application.acceso.ReservarPistaConAcceso;
import org.pistaberta.application.reserva.CancelarReserva;
import org.pistaberta.domain.reserva.Franxa;
import org.pistaberta.domain.reserva.PistaId;
import org.pistaberta.domain.reserva.ReservaId;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * API mínima (D-012): solo invoca los casos de uso; sin listados, catálogo ni auth. El tenant lo fija
 * {@code TenantFilter} (cabecera en dev/test; en prod no hay resolver y la petición falla cerrada).
 */
@RestController
class ReservaController {

    private final ReservarPistaConAcceso reservar;
    private final CancelarReserva cancelar;

    ReservaController(ReservarPistaConAcceso reservar, CancelarReserva cancelar) {
        this.reservar = reservar;
        this.cancelar = cancelar;
    }

    record ReservarRequest(OffsetDateTime inicio, OffsetDateTime fin) {}

    record ReservaCreada(UUID reservaId, OffsetDateTime inicio, OffsetDateTime fin, String estado, String token) {

        /** El token es un ticket al portador: el {@code toString} automático del record lo imprimiría en claro. */
        @Override
        public String toString() {
            return "ReservaCreada[reservaId=" + reservaId + ", estado=" + estado + ", token=***]";
        }
    }

    @PostMapping("/api/pistas/{pistaId}/reservas")
    ResponseEntity<ReservaCreada> reservar(@PathVariable("pistaId") UUID pistaId, @RequestBody ReservarRequest peticion) {
        if (peticion.inicio() == null || peticion.fin() == null) {
            throw new SolicitudInvalida("inicio y fin son obligatorios");
        }
        ReservaConAcceso resultado = reservar.executar(new PistaId(pistaId),
                new Franxa(peticion.inicio().toInstant(), peticion.fin().toInstant()));
        ReservaCreada cuerpo = new ReservaCreada(
                resultado.reserva().id().value(),
                utc(resultado.reserva().franxa().inicio()),
                utc(resultado.reserva().franxa().fin()),
                "confirmada",
                resultado.token().valor());
        // El token solo viaja aquí: la respuesta no debe cachearse.
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(cuerpo);
    }

    /** La respuesta refleja la franxa persistida (normalizada a microsegundos), no lo que envió el cliente. */
    private static OffsetDateTime utc(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    @PostMapping("/api/reservas/{id}/cancelar")
    ResponseEntity<Void> cancelar(@PathVariable("id") UUID id) {
        cancelar.executar(new ReservaId(id));
        return ResponseEntity.noContent().build();
    }
}
