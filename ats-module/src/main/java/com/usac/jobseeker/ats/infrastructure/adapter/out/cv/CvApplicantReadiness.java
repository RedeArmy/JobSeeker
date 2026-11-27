package com.usac.jobseeker.ats.infrastructure.adapter.out.cv;

import com.usac.jobseeker.ats.application.usecase.submitapplication.ApplicantReadinessPort;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness.ApplicationReadiness;
import com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness.CheckApplicationReadinessQuery;
import com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness.CheckApplicationReadinessUseCase;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `cv` si el candidato cumple BR-CAND-01, con su caso de uso público (ARCH-04). Los
 * requisitos que faltan viajan como los códigos estables de `cv` (`FULL_NAME`, `CONTACT`, `CV`),
 * que son los que la API devuelve.
 */
@Component
public class CvApplicantReadiness implements ApplicantReadinessPort {

  private final CheckApplicationReadinessUseCase readiness;

  public CvApplicantReadiness(CheckApplicationReadinessUseCase readiness) {
    this.readiness = readiness;
  }

  @Override
  public Readiness check(AccountId candidate) {
    ApplicationReadiness answer = readiness.execute(new CheckApplicationReadinessQuery(candidate));
    return new Readiness(
        answer.missing().stream().map(Enum::name).sorted().toList(),
        answer.candidateProfileId(),
        answer.currentCvFileId());
  }
}
