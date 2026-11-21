package com.usac.archfixture.good.job.infrastructure.adapter.out.cv;

import com.usac.archfixture.good.cv.application.usecase.OkUseCase;
import com.usac.archfixture.good.cv.domain.event.CvUpdated;
import org.springframework.scheduling.annotation.Scheduled;

/** Conforme: otro módulo se usa solo desde un adaptador y solo por su caso de uso y su evento. */
public class CvAdapter {
  OkUseCase useCase;
  CvUpdated lastEvent;

  @Scheduled(fixedDelay = 1000)
  void poll() {}
}
