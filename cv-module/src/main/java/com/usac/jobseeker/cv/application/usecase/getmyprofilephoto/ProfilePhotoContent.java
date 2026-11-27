package com.usac.jobseeker.cv.application.usecase.getmyprofilephoto;

import java.util.Arrays;
import java.util.Objects;

/** El contenido de la foto para servirla, con el tipo MIME con que se guardó. */
public record ProfilePhotoContent(byte[] content, String mediaType) {

  public ProfilePhotoContent {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(mediaType, "mediaType");
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ProfilePhotoContent(byte[] bytes, String type)
        && mediaType.equals(type)
        && Arrays.equals(content, bytes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mediaType, Arrays.hashCode(content));
  }

  @Override
  public String toString() {
    return "ProfilePhotoContent[" + mediaType + ", bytes=" + content.length + "]";
  }
}
