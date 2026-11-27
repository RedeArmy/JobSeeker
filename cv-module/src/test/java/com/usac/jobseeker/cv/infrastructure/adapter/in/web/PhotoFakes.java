package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.GetMyProfilePhotoQuery;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.GetMyProfilePhotoUseCase;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.ProfilePhotoContent;
import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.ProfilePhotoView;
import java.util.Optional;

/** Una foto en memoria que los dos controladores de la foto leen. */
final class PhotoFakes implements GetMyProfilePhotoUseCase {

  static final ProfilePhotoView VIEW = new ProfilePhotoView("PNG", "abcdef123456");
  static final byte[] BYTES = {1, 2, 3};

  boolean present = true;

  @Override
  public Optional<ProfilePhotoView> describe(GetMyProfilePhotoQuery query) {
    return present ? Optional.of(VIEW) : Optional.empty();
  }

  @Override
  public Optional<ProfilePhotoContent> read(GetMyProfilePhotoQuery query) {
    return present ? Optional.of(new ProfilePhotoContent(BYTES, "image/png")) : Optional.empty();
  }
}
