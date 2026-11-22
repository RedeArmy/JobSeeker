package com.usac.archfixture.arch11.common;

import org.springframework.util.Assert;

/** Viola ARCH-11: `common` depende de un framework. */
public class BadCommon {
  public static void check(Object value) {
    Assert.notNull(value, "value");
  }
}
