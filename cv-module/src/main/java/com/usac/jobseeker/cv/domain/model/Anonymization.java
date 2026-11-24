package com.usac.jobseeker.cv.domain.model;

/**
 * Lo que queda en el lugar de un dato personal anonimizado (BR-DATA-05, ADR-0013). El nombre es
 * obligatorio en el perfil profesional, así que se sustituye por un marcador genérico en vez de
 * borrarse; no identifica a nadie y quien lo lea sabe que la persona pidió ser olvidada.
 */
public final class Anonymization {

  public static final String PLACEHOLDER_NAME = "Candidato eliminado";

  private Anonymization() {}
}
