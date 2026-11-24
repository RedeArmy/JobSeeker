package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import java.util.List;
import org.junit.jupiter.api.Test;

/** El formato de texto de la pantalla y sus mensajes: lo único propio de la interfaz. */
class CandidateScreenSupportTest {

  @Test
  void skillLinesAreReadWithOrWithoutLevelAndBlankLinesAreIgnored() {
    assertThat(CandidateSetsForm.skills("Java;4\n\n  SQL  \nGo;"))
        .containsExactly(
            new CandidateSkill("Java", 4),
            new CandidateSkill("SQL", null),
            new CandidateSkill("Go", null));
    assertThat(CandidateSetsForm.skills(null)).isEmpty();
  }

  @Test
  void aSkillLevelThatIsNotANumberPointsAtItsLine() {
    assertThatThrownBy(() -> CandidateSetsForm.skills("Java\nSQL;alto"))
        .isInstanceOf(ValidationFailedException.class)
        .extracting(e -> ((ValidationFailedException) e).violations())
        .isEqualTo(List.of(new FieldViolation("skills[1].level", "OUT_OF_RANGE")));
  }

  @Test
  void languageLinesAreReadCaseInsensitivelyAndAnUnknownLevelIsRejected() {
    assertThat(CandidateSetsForm.languages("en;b2\nes;native"))
        .containsExactly(
            new CandidateLanguage("en", LanguageProficiency.B2),
            new CandidateLanguage("es", LanguageProficiency.NATIVE));
    assertThatThrownBy(() -> CandidateSetsForm.languages("en;fluido"))
        .isInstanceOf(ValidationFailedException.class);
    assertThat(CandidateSetsForm.languages("en").get(0).proficiency()).isNull();
  }

  @Test
  void countriesAcceptCommasSpacesAndNewlines() {
    assertThat(CandidateSetsForm.countries("GT, MX\nUS  CA"))
        .containsExactly("GT", "MX", "US", "CA");
    assertThat(CandidateSetsForm.countries("  ")).isEmpty();
    assertThat(CandidateSetsForm.countries(null)).isEmpty();
  }

  @Test
  void theTextOfASetCanBeReadBackAsTheSameSet() {
    var skills = List.of(new CandidateSkill("Java", 4), new CandidateSkill("SQL", null));
    var languages = List.of(new CandidateLanguage("en", LanguageProficiency.C1));

    assertThat(CandidateSetsForm.skills(CandidateSetsForm.skillsText(skills))).isEqualTo(skills);
    assertThat(CandidateSetsForm.languages(CandidateSetsForm.languagesText(languages)))
        .isEqualTo(languages);
  }

  @Test
  void messagesNameTheLineOfASetElementAndNeverEchoAValue() {
    var messages =
        CandidateMessages.describe(
            List.of(
                new FieldViolation("skills[2].name", "DUPLICATE"),
                new FieldViolation("languages[0].language", "INVALID_LANGUAGE"),
                new FieldViolation("workAuthorizations[1]", "INVALID_COUNTRY"),
                new FieldViolation("contactEmail", "INVALID_EMAIL"),
                new FieldViolation("campoRaro", "CODIGO_RARO")));

    assertThat(messages)
        .containsExactly(
            "La línea 3 de las habilidades (el nombre) está repetido.",
            "La línea 1 de los idiomas (el código) no es un código de idioma válido (por ejemplo es).",
            "La línea 2 de las autorizaciones de trabajo no es un código de país válido (por ejemplo GT).",
            "El correo de contacto no parece un correo válido.",
            "Un dato no es válido.");
  }
}
