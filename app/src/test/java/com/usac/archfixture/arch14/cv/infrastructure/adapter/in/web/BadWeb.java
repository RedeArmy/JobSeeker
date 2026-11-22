package com.usac.archfixture.arch14.cv.infrastructure.adapter.in.web;

import com.usac.archfixture.arch14.cv.infrastructure.adapter.out.persistence.Repo;

/** Viola ARCH-14: un adaptador de entrada que usa directamente uno de salida. */
public class BadWeb {
  String read(Repo repo) {
    return repo.load();
  }
}
