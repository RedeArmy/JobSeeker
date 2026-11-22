package com.usac.archfixture.arch09.cv.application.usecase;

import org.springframework.scheduling.annotation.Scheduled;

/** Viola ARCH-09: un @Scheduled en application. */
public class BadScheduled {
  @Scheduled(fixedDelay = 1000)
  void tick() {
    // Cuerpo vacío a propósito: la clase solo existe para que ARCH-09 detecte un @Scheduled fuera
    // de infrastructure.
  }
}
