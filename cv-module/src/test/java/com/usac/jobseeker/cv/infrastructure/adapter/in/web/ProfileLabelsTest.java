package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.cv.domain.model.EducationLevel;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProfileLabelsTest {

  @Test
  void everyLevelHasAReadableLabelThatIsNotItsInternalCode() {
    for (EducationLevel level : EducationLevel.values()) {
      assertThat(ProfileLabels.level(level))
          .isNotBlank()
          .doesNotContain("_")
          .isNotEqualTo(level.name());
    }
  }

  @Test
  void theLabelsAreDistinctSoThePersonCanTellThemApart() {
    assertThat(Arrays.stream(EducationLevel.values()).map(ProfileLabels::level).distinct())
        .hasSize(EducationLevel.values().length);
  }
}
