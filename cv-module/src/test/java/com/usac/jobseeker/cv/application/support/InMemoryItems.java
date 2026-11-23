package com.usac.jobseeker.cv.application.support;

import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfileCollectionsReadPort;
import com.usac.jobseeker.cv.application.usecase.managecertifications.CertificationStorePort;
import com.usac.jobseeker.cv.application.usecase.manageeducation.EducationStorePort;
import com.usac.jobseeker.cv.application.usecase.manageexperiences.ExperienceStorePort;
import com.usac.jobseeker.cv.application.usecase.manageprojects.ProjectStorePort;
import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.Project;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Las cuatro colecciones en memoria, con la misma regla de propiedad que el adaptador JDBC: un
 * elemento solo se encuentra si pertenece al perfil que lo pide. Una sola clase no puede
 * implementar los cuatro puertos porque cada uno cuenta y borra en su propia colección, así que
 * cada uno es una clase interna.
 */
public class InMemoryItems implements ProfileCollectionsReadPort {

  /** Almacén genérico de una colección, por perfil dueño. */
  static class Store<T> {
    final Map<ProfessionalProfileId, Map<ProfileItemId, T>> byProfile = new LinkedHashMap<>();

    int count(ProfessionalProfileId profile) {
      return byProfile.getOrDefault(profile, Map.of()).size();
    }

    void insert(ProfessionalProfileId profile, ProfileItemId id, T item) {
      byProfile.computeIfAbsent(profile, p -> new LinkedHashMap<>()).put(id, item);
    }

    boolean update(ProfessionalProfileId profile, ProfileItemId id, T item) {
      Map<ProfileItemId, T> items = byProfile.get(profile);
      if (items == null || !items.containsKey(id)) {
        return false;
      }
      items.put(id, item);
      return true;
    }

    boolean delete(ProfessionalProfileId profile, ProfileItemId id) {
      Map<ProfileItemId, T> items = byProfile.get(profile);
      return items != null && items.remove(id) != null;
    }

    List<T> list(ProfessionalProfileId profile) {
      return new ArrayList<>(byProfile.getOrDefault(profile, Map.of()).values());
    }
  }

  private final Store<Experience> experienceStore = new Store<>();
  private final Store<Education> educationStore = new Store<>();
  private final Store<Certification> certificationStore = new Store<>();
  private final Store<Project> projectStore = new Store<>();

  public final ExperienceStorePort experiences =
      new ExperienceStorePort() {
        public int count(ProfessionalProfileId p) {
          return experienceStore.count(p);
        }

        public void insert(ProfessionalProfileId p, Experience i) {
          experienceStore.insert(p, i.id(), i);
        }

        public boolean update(ProfessionalProfileId p, Experience i) {
          return experienceStore.update(p, i.id(), i);
        }

        public boolean delete(ProfessionalProfileId p, ProfileItemId id) {
          return experienceStore.delete(p, id);
        }
      };

  public final EducationStorePort education =
      new EducationStorePort() {
        public int count(ProfessionalProfileId p) {
          return educationStore.count(p);
        }

        public void insert(ProfessionalProfileId p, Education i) {
          educationStore.insert(p, i.id(), i);
        }

        public boolean update(ProfessionalProfileId p, Education i) {
          return educationStore.update(p, i.id(), i);
        }

        public boolean delete(ProfessionalProfileId p, ProfileItemId id) {
          return educationStore.delete(p, id);
        }
      };

  public final CertificationStorePort certifications =
      new CertificationStorePort() {
        public int count(ProfessionalProfileId p) {
          return certificationStore.count(p);
        }

        public void insert(ProfessionalProfileId p, Certification i) {
          certificationStore.insert(p, i.id(), i);
        }

        public boolean update(ProfessionalProfileId p, Certification i) {
          return certificationStore.update(p, i.id(), i);
        }

        public boolean delete(ProfessionalProfileId p, ProfileItemId id) {
          return certificationStore.delete(p, id);
        }
      };

  public final ProjectStorePort projects =
      new ProjectStorePort() {
        public int count(ProfessionalProfileId p) {
          return projectStore.count(p);
        }

        public void insert(ProfessionalProfileId p, Project i) {
          projectStore.insert(p, i.id(), i);
        }

        public boolean update(ProfessionalProfileId p, Project i) {
          return projectStore.update(p, i.id(), i);
        }

        public boolean delete(ProfessionalProfileId p, ProfileItemId id) {
          return projectStore.delete(p, id);
        }
      };

  @Override
  public ProfileCollections findByProfileId(ProfessionalProfileId profileId) {
    return new ProfileCollections(
        experienceStore.list(profileId),
        educationStore.list(profileId),
        certificationStore.list(profileId),
        projectStore.list(profileId));
  }
}
