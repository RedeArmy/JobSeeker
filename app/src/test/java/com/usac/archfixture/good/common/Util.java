package com.usac.archfixture.good.common;

import java.util.Objects;

/** Conforme con ARCH-11: `common` solo usa el JDK. */
public final class Util {
  private Util() {}

  public static String required(String value) {
    return Objects.requireNonNull(value);
  }
}
