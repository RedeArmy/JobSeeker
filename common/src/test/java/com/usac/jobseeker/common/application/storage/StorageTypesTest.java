package com.usac.jobseeker.common.application.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.storage.MalwareScanPort.ScanVerdict;
import com.usac.jobseeker.common.domain.error.FileRejectedException;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class StorageTypesTest {

  @Test
  void aRandomKeyHasThePurposePrefixAndNothingAboutTheOwner() {
    var cv = StoredObjectKey.random(FilePurpose.CV);
    var photo = StoredObjectKey.random(FilePurpose.PHOTO);

    assertThat(cv.value()).startsWith("cv/").hasSize(3 + 36);
    assertThat(photo.value()).startsWith("photo/");
    assertThat(StoredObjectKey.random(FilePurpose.CV)).isNotEqualTo(cv);
  }

  // Una clave leída de una tabla se valida otra vez: nada arbitrario llega al almacén.
  @Test
  void onlyWellFormedKeysAreAccepted() {
    var valid = StoredObjectKey.random(FilePurpose.ANSWER).value();

    assertThat(new StoredObjectKey(valid).value()).isEqualTo(valid);
    assertThatThrownBy(() -> new StoredObjectKey("cv/../../secreto"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StoredObjectKey("otros/" + java.util.UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StoredObjectKey(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void theVerdictAndTheErrorsCarryOnlyWhatTheyNeed() {
    ScanVerdict infected = new ScanVerdict.Infected("Eicar-Test-Signature");
    var rejected = new FileRejectedException(FileRejectedException.Reason.MALWARE_DETECTED);
    var unavailable = new UpstreamUnavailableException("storage", new IOException("caído"));

    assertThat(infected).isInstanceOf(ScanVerdict.Infected.class);
    assertThat(new ScanVerdict.Clean()).isInstanceOf(ScanVerdict.class);
    assertThat(rejected.reason()).isEqualTo(FileRejectedException.Reason.MALWARE_DETECTED);
    assertThat(unavailable.dependency()).isEqualTo("storage");
    assertThat(unavailable).hasCauseInstanceOf(IOException.class);
    assertThatThrownBy(() -> new ScanVerdict.Infected(null))
        .isInstanceOf(NullPointerException.class);
  }
}
