package com.usac.archfixture.good.cv.application.usecase;

import com.usac.archfixture.good.cv.domain.model.Ok;
import org.springframework.transaction.annotation.Transactional;

/** Conforme: el único Spring que usa application es el de transacciones (ARCH-02). */
public class OkUseCaseImpl implements OkUseCase {
  @Override
  @Transactional
  public String run(String input) {
    return new Ok(input).value();
  }
}
