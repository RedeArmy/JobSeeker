package com.usac.archfixture.arch04b.cv.infrastructure.adapter.out;

import com.usac.archfixture.arch04b.job.infrastructure.adapter.out.JobAdapter;

/** Viola ARCH-04: un adaptador usa un tipo interno (no de la superficie pública) de otro módulo. */
public class BadAdapter {
  JobAdapter jobAdapter;
}
