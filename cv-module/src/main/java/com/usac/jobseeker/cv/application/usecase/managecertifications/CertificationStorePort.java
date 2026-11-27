package com.usac.jobseeker.cv.application.usecase.managecertifications;

import com.usac.jobseeker.cv.application.support.ProfileItemStorePort;
import com.usac.jobseeker.cv.domain.model.Certification;

/**
 * Puerto de salida de las certificaciones. Toda operación sobre un elemento lleva el perfil dueño:
 * un identificador de otro perfil no se encuentra, ni se modifica ni se borra (BR-ACCT-04).
 */
public interface CertificationStorePort extends ProfileItemStorePort<Certification> {}
