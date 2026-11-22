package com.usac.archfixture.arch13.cv.infrastructure.config;

import org.springframework.beans.factory.annotation.Autowired;

/** Viola ARCH-13: inyección por campo. */
public class BadInjection {
  @Autowired Object dependency;
}
