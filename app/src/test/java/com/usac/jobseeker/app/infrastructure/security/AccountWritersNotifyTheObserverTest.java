package com.usac.jobseeker.app.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Garantía estructural de la memoria por petición de la resolución de actor: **todo** código que
 * escribe la tabla `accounts` avisa de ello. Si alguien añade un escritor nuevo (la gestión de
 * capacidades, la solicitud de eliminación, la anonimización), esta prueba falla hasta que llame a
 * `AccountChangeObserver`, así que nadie puede dejar a una petición viendo la cuenta de antes.
 */
class AccountWritersNotifyTheObserverTest {

  private static final Pattern WRITES_ACCOUNTS =
      Pattern.compile(
          "\"[^\"]*\\b(insert\\s+into|update|delete\\s+from)\\s+accounts\\b",
          Pattern.CASE_INSENSITIVE);

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isDirectory(current.resolve("auth-module"))) {
      current = current.getParent();
    }
    assertThat(current).as("raíz del repositorio").isNotNull();
    return current;
  }

  private static List<Path> productionSources() throws IOException {
    try (Stream<Path> modules = Files.list(repositoryRoot())) {
      List<Path> roots =
          modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).toList();
      return roots.stream()
          .flatMap(
              root -> {
                try {
                  return Files.walk(root).filter(p -> p.toString().endsWith(".java"));
                } catch (IOException failure) {
                  throw new java.io.UncheckedIOException(failure);
                }
              })
          .toList();
    }
  }

  @Test
  void everyProductionWriterOfAccountsNotifiesTheObserver() throws IOException {
    List<Path> writers = new java.util.ArrayList<>();
    for (Path source : productionSources()) {
      if (WRITES_ACCOUNTS.matcher(Files.readString(source)).find()) {
        writers.add(source);
      }
    }

    assertThat(writers)
        .as("escritores de `accounts` en código de producción")
        .extracting(p -> p.getFileName().toString())
        .containsExactlyInAnyOrder(
            "JdbcAccountRepository.java", "JdbcIdentityStateRepository.java");
    for (Path writer : writers) {
      assertThat(Files.readString(writer))
          .as("%s debe avisar con observer.accountChanged(...)", writer.getFileName())
          .contains("observer.accountChanged(");
    }
  }

  @Test
  void theDetectorFindsTheWritesItIsMeantToFind() {
    assertThat(WRITES_ACCOUNTS.matcher("\"insert into accounts (id) values (:id)\"").find())
        .isTrue();
    assertThat(WRITES_ACCOUNTS.matcher("\"update accounts set identity_status = :s\"").find())
        .isTrue();
    assertThat(WRITES_ACCOUNTS.matcher("\"delete from accounts where id = :id\"").find()).isTrue();
    assertThat(WRITES_ACCOUNTS.matcher("\"select id from accounts where id = :id\"").find())
        .isFalse();
    assertThat(WRITES_ACCOUNTS.matcher("\"update professional_profiles set x = 1\"").find())
        .isFalse();
  }
}
