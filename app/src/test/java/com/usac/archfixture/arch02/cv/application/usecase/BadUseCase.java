package com.usac.archfixture.arch02.cv.application.usecase;

import jakarta.persistence.EntityManager;

/** Viola ARCH-02: un caso de uso que depende de JPA. */
public class BadUseCase {
  EntityManager entityManager;
}
