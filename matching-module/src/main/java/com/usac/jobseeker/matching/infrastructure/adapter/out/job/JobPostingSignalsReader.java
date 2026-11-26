package com.usac.jobseeker.matching.infrastructure.adapter.out.job;

import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsQuery;
import com.usac.jobseeker.job.application.usecase.getjobsignals.GetJobSignalsUseCase;
import com.usac.jobseeker.job.application.usecase.getjobsignals.JobSnapshot;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobSignalsReader;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Capa anticorrupción hacia `job` (ARCH-04): convierte la lectura pública de `job` en la proyección
 * propia de `matching`. Es el único lugar de `matching` que nombra un tipo de `job`. Lo que llega
 * ya viene sin el reclutador ni las fechas de gestión (`job` lo garantiza en su origen) y {@link
 * JobSignals} no tiene dónde guardarlos.
 *
 * <p>Se registra a sí mismo como componente porque es el único lugar donde `matching` puede nombrar
 * un tipo de `job`: la configuración (`infrastructure.config`) solo ve el puerto propio.
 */
@Component
public class JobPostingSignalsReader implements JobSignalsReader {

  private static final String PUBLISHED = "PUBLISHED";
  private static final String REVIEWING = "REVIEWING";

  private final GetJobSignalsUseCase signals;

  public JobPostingSignalsReader(GetJobSignalsUseCase signals) {
    this.signals = signals;
  }

  @Override
  public Optional<JobSignals> read(JobPostingId id) {
    return signals.execute(new GetJobSignalsQuery(id.value())).map(this::translate);
  }

  // Ante un estado desconocido la vacante no se considera publicada: ante la duda no se puntúa.
  private JobSignals translate(JobSnapshot s) {
    return new JobSignals(
        new JobPostingId(s.jobPostingId()),
        s.version(),
        PUBLISHED.equals(s.status()),
        REVIEWING.equals(s.recruitingStage()),
        s.status(),
        s.title(),
        s.description(),
        s.modality(),
        s.country(),
        s.salary() == null
            ? null
            : new JobSignals.Salary(
                s.salary().min(), s.salary().max(), s.salary().currency(), s.salary().period()),
        s.requirements().stream().map(JobPostingSignalsReader::requirement).toList());
  }

  private static JobSignals.Requirement requirement(JobSnapshot.Requirement r) {
    return new JobSignals.Requirement(
        r.type(),
        r.mandatory(),
        r.skillName(),
        r.minLevel(),
        r.educationLevel(),
        r.minYears(),
        r.language(),
        r.minProficiency());
  }
}
