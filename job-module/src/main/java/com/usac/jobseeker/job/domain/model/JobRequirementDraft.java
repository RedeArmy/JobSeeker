package com.usac.jobseeker.job.domain.model;

/**
 * Un requisito tal como llega del cliente, sin validar. Los campos que no son del tipo se ignoran
 * al validar solo si vienen vacíos: uno que sí trae un valor ajeno al tipo es un error del cliente
 * y se rechaza, para que nadie crea que un dato se guardó cuando no se usa.
 */
public record JobRequirementDraft(
    RequirementType type,
    Boolean mandatory,
    String skillName,
    Integer minLevel,
    EducationLevel educationLevel,
    Integer minYears,
    String language,
    LanguageProficiency minProficiency) {}
