package com.usac.jobseeker.common.application.idempotency;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Optional;

/**
 * Las claves de idempotencia de las peticiones que crean recursos (`Idempotency-Key`, LLD §7): la
 * misma clave de la misma cuenta devuelve lo que se guardó en vez de repetir el efecto.
 *
 * <p>Se usa en la **misma transacción** que el efecto, en dos pasos: {@link #claim} reserva la
 * clave antes de hacer nada y {@link #complete} guarda la respuesta al final. Si el efecto falla,
 * la reserva se deshace con él y la clave sirve para reintentar. Dos peticiones simultáneas con la
 * misma clave no hacen el efecto dos veces: la segunda espera en la reserva a que la primera
 * confirme, y entonces recibe su respuesta.
 */
public interface IdempotencyKeysPort {

  /**
   * Reserva la clave para esta petición.
   *
   * @param requestHash huella de la petición (SHA-256 en hexadecimal)
   * @return vacío si la clave es nueva y hay que hacer el efecto; la respuesta guardada si ya se
   *     hizo
   * @throws com.usac.jobseeker.common.domain.error.IdempotencyKeyReusedException si la clave ya se
   *     usó con otra petición
   */
  Optional<String> claim(AccountId account, String key, String requestHash);

  /** Guarda la respuesta de una clave reservada en esta misma transacción. */
  void complete(AccountId account, String key, String response);
}
