package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CurrentActorTest {

  @Test
  void aRequestWithoutAnActorHasNone() {
    assertThat(CurrentActor.from(new MockHttpServletRequest())).isEmpty();
  }

  @Test
  void theActorThatTheFilterPlacedOnTheRequestIsReturned() {
    Actor actor = new Actor(new AccountId(UUID.randomUUID()), Set.of(Capability.RECRUITER));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CurrentActor.ATTRIBUTE, actor);

    assertThat(CurrentActor.from(request)).contains(actor);
  }
}
