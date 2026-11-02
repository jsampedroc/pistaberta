package org.pistaberta.infrastructure.reserva;

import org.pistaberta.application.reserva.CancelarReserva;
import org.pistaberta.application.reserva.ReservaRepository;
import org.pistaberta.application.reserva.ReservarPista;
import org.pistaberta.application.reserva.Transaccion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Los casos de uso son Java plano en application; aquí se cablean. */
@Configuration
class ReservaConfig {

    @Bean
    ReservarPista reservarPista(ReservaRepository reservas, Transaccion transaccion) {
        return new ReservarPista(reservas, transaccion);
    }

    @Bean
    CancelarReserva cancelarReserva(ReservaRepository reservas, Transaccion transaccion) {
        return new CancelarReserva(reservas, transaccion);
    }
}
