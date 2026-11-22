package com.usac.archfixture.arch12.cv.application.usecase;

/** Viola ARCH-12: un puerto que es una clase concreta y no una interfaz. */
public class ConcretePort {
  public boolean save(String value) {
    return value != null;
  }
}
