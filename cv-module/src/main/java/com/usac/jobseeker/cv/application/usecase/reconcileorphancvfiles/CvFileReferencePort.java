package com.usac.jobseeker.cv.application.usecase.reconcileorphancvfiles;

import java.util.Collection;
import java.util.Set;

/** Puerto de salida: qué claves del almacén referencia todavía alguna versión del CV. */
public interface CvFileReferencePort {

  /** De las claves dadas, las que tienen fila viva; una consulta para todas (TD-039). */
  Set<String> referenced(Collection<String> keys);
}
