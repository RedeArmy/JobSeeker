package com.usac.archfixture.good.cv.application.usecase;

/** Conforme con ARCH-12: un puerto es una interfaz. */
public interface OkPort {
  boolean save(String value);
}
