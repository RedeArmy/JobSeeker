package com.usac.jobseeker.cv.application.usecase.savecandidateprofile;

import com.usac.jobseeker.cv.application.usecase.getmycandidateprofile.CandidateProfileView;
import java.util.Objects;

/** El perfil ya guardado y si esta llamada lo creó, para que la API responda 201 o 200. */
public record SaveCandidateProfileResult(CandidateProfileView profile, boolean created) {

  public SaveCandidateProfileResult {
    Objects.requireNonNull(profile, "profile");
  }
}
