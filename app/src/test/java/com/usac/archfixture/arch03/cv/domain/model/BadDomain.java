package com.usac.archfixture.arch03.cv.domain.model;

import com.usac.archfixture.arch03.cv.infrastructure.adapter.out.Infra;

/** Viola ARCH-03: el dominio depende de infrastructure. */
public class BadDomain {
  Infra infra;
}
