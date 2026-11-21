package com.usac.jobseeker.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada del único desplegable (ADR-0026). Escanea todo `com.usac.jobseeker` para
 * registrar la configuración de `infrastructure` de cada módulo; los casos de uso se cablean allí
 * como beans, nunca con estereotipos en `application/` (ADR-0015, ARCH-02).
 */
@SpringBootApplication(scanBasePackages = "com.usac.jobseeker")
public class JobSeekerApplication {

  public static void main(String[] args) {
    SpringApplication.run(JobSeekerApplication.class, args);
  }
}
