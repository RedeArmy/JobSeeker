package com.usac.jobseeker.cv.application.usecase.saveprofessionalprofile;

import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import java.util.Objects;

/** El perfil ya guardado y si esta llamada lo creó, para que la API responda 201 o 200. */
public record SaveProfessionalProfileResult(ProfessionalProfileView profile, boolean created) {

  public SaveProfessionalProfileResult {
    Objects.requireNonNull(profile, "profile");
  }
}
