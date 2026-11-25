package com.usac.jobseeker.app.infrastructure.security.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Quién es operador lo decide la configuración, y por defecto nadie lo es. */
class ConfiguredOperatorsTest {

  @Test
  void onlyTheConfiguredAccountsAreOperators() {
    UUID ana = UUID.randomUUID();
    UUID luis = UUID.randomUUID();
    var operators = new ConfiguredOperators(new OperatorProperties(List.of(ana, luis)));

    assertThat(operators.isOperator(new AccountId(ana))).isTrue();
    assertThat(operators.isOperator(new AccountId(luis))).isTrue();
    assertThat(operators.isOperator(new AccountId(UUID.randomUUID()))).isFalse();
  }

  @Test
  void withoutConfigurationNobodyIsAnOperator() {
    assertThat(
            new ConfiguredOperators(new OperatorProperties(null))
                .isOperator(new AccountId(UUID.randomUUID())))
        .isFalse();
    assertThat(
            new ConfiguredOperators(new OperatorProperties(List.of()))
                .isOperator(new AccountId(UUID.randomUUID())))
        .isFalse();
  }

  @Test
  void theListIsAnImmutableCopy() {
    var mutable = new java.util.ArrayList<UUID>();
    UUID ana = UUID.randomUUID();
    mutable.add(ana);
    var properties = new OperatorProperties(mutable);

    mutable.add(UUID.randomUUID());

    assertThat(properties.accountIds()).containsExactly(ana);
  }
}
