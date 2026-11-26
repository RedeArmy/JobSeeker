package com.usac.jobseeker.cv.application.usecase.uploadcv;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Arrays;
import java.util.Objects;

/**
 * Subir una versión del CV. El contenido se copia al entrar y al salir: es un arreglo mutable y el
 * caso de uso lo valida y lo escanea, así que nadie debe poder cambiarlo entre medias.
 *
 * @param originalName el nombre que envió el cliente; solo se usa, saneado, para mostrarlo
 */
public record UploadCvCommand(AccountId accountId, String originalName, byte[] content) {

  public UploadCvCommand {
    Objects.requireNonNull(accountId, "accountId");
    content = content == null ? new byte[0] : content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof UploadCvCommand that
        && accountId.equals(that.accountId)
        && Objects.equals(originalName, that.originalName)
        && Arrays.equals(content, that.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accountId, originalName, Arrays.hashCode(content));
  }

  @Override
  public String toString() {
    // Ni el nombre ni el contenido van a un log por accidente (BR-DATA-01).
    return "UploadCvCommand[bytes=" + content.length + "]";
  }
}
