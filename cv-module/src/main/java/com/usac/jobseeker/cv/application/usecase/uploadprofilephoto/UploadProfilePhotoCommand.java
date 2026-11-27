package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Arrays;
import java.util.Objects;

/**
 * Subir la foto del perfil profesional. El contenido se copia al entrar y al salir, como en el CV:
 * es un arreglo mutable que el caso de uso valida y escanea.
 */
public record UploadProfilePhotoCommand(AccountId accountId, byte[] content) {

  public UploadProfilePhotoCommand {
    Objects.requireNonNull(accountId, "accountId");
    content = content == null ? new byte[0] : content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof UploadProfilePhotoCommand(AccountId account, byte[] bytes)
        && accountId.equals(account)
        && Arrays.equals(content, bytes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accountId, Arrays.hashCode(content));
  }

  @Override
  public String toString() {
    // La imagen nunca va a un log por accidente (BR-DATA-01).
    return "UploadProfilePhotoCommand[bytes=" + content.length + "]";
  }
}
