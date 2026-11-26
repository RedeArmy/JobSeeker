package com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import java.util.Collection;
import java.util.Set;

/**
 * Puerto de salida: qué claves del almacén referencia todavía `cv`, sea una versión del CV o la
 * foto de un perfil.
 */
public interface CvFileReferencePort {

  /** De las claves dadas de ese propósito, las que siguen en uso; una consulta para todas. */
  Set<String> referenced(FilePurpose purpose, Collection<String> keys);
}
