package com.usac.archfixture.good.cv.infrastructure.adapter.in.web;

import com.usac.archfixture.good.cv.application.usecase.OkUseCase;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OkController {
  OkUseCase useCase;
}
