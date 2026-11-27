package com.usac.jobseeker.cv.application.support;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * El flujo de añadir, reemplazar y quitar un elemento de una colección del perfil, escrito una sola
 * vez para las cuatro (TD-013). Antes cada servicio lo repetía: validar el elemento antes de tocar
 * la base, respetar el máximo al añadir, responder 404 si el elemento no es de quien llama. Una
 * regla nueva se cambia aquí y vale para todas.
 *
 * <p>La versión del perfil, el evento, la métrica y el log los pone {@link ProfileChange}. Cada
 * servicio solo aporta cómo se construye su elemento a partir del comando. La transacción la abre
 * el servicio, que sigue siendo el caso de uso (ADR-0016).
 *
 * @param <T> el elemento de la colección
 */
public final class ProfileItemChanges<T> {

  private final String useCase;
  private final String field;
  private final String changeKind;
  private final ProfileItemStorePort<T> items;
  private final ProfileChange change;

  /**
   * @param useCase nombre estable del caso de uso, para la métrica
   * @param field nombre del campo de la colección en los errores de validación
   * @param changeKind tipo de cambio que lleva el evento `ProfileUpdated`
   */
  public ProfileItemChanges(
      String useCase,
      String field,
      String changeKind,
      ProfileItemStorePort<T> items,
      ProfileChange change) {
    this.useCase = Objects.requireNonNull(useCase, "useCase");
    this.field = Objects.requireNonNull(field, "field");
    this.changeKind = Objects.requireNonNull(changeKind, "changeKind");
    this.items = Objects.requireNonNull(items, "items");
    this.change = Objects.requireNonNull(change, "change");
  }

  /**
   * Añade un elemento con un identificador nuevo; falla con `TOO_MANY` si la colección está llena.
   */
  public ProfessionalProfileView add(
      AccountId account, long expectedVersion, Function<ProfileItemId, T> build) {
    T item = change.validated(useCase, () -> build.apply(change.newItemId()));
    return change.apply(
        useCase,
        "added",
        account,
        expectedVersion,
        changeKind,
        profile -> {
          change.requireRoom(useCase, field, items.count(profile));
          items.insert(profile, item);
        });
  }

  /** Reemplaza el elemento; 404 si no existe o es de otro perfil. */
  public ProfessionalProfileView update(
      AccountId account, long expectedVersion, UUID itemId, Function<ProfileItemId, T> build) {
    T item = change.validated(useCase, () -> build.apply(new ProfileItemId(itemId)));
    return change.apply(
        useCase,
        "updated",
        account,
        expectedVersion,
        changeKind,
        profile -> {
          if (!items.update(profile, item)) {
            throw new NotFoundException();
          }
        });
  }

  /** Quita el elemento; 404 si no existe o es de otro perfil. */
  public ProfessionalProfileView remove(AccountId account, long expectedVersion, UUID itemId) {
    return change.apply(
        useCase,
        "removed",
        account,
        expectedVersion,
        changeKind,
        profile -> {
          if (!items.delete(profile, new ProfileItemId(itemId))) {
            throw new NotFoundException();
          }
        });
  }
}
