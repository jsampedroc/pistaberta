package org.pistaberta.infrastructure.acceso;

import org.pistaberta.application.acceso.EmitirAcceso;
import org.pistaberta.application.acceso.GeneradorToken;
import org.pistaberta.application.acceso.RepositorioAcceso;
import org.pistaberta.application.acceso.ReservarPistaConAcceso;
import org.pistaberta.application.reserva.ReservarPista;
import org.pistaberta.application.reserva.Transaccion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AccesoConfig {

    @Bean
    EmitirAcceso emitirAcceso(GeneradorToken generador, RepositorioAcceso accesos) {
        return new EmitirAcceso(generador, accesos);
    }

    @Bean
    ReservarPistaConAcceso reservarPistaConAcceso(ReservarPista reservar, EmitirAcceso emitir, Transaccion transaccion) {
        return new ReservarPistaConAcceso(reservar, emitir, transaccion);
    }
}
