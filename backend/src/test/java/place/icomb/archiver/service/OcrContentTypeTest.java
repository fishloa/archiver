package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OcrContentTypeTest {

  @Test
  void exposesTheTwoMediaTypesThePipelineProduces() {
    assertThat(OcrContentType.PLAIN).isEqualTo("text/plain");
    assertThat(OcrContentType.MARKDOWN).isEqualTo("text/markdown");
  }

  @Test
  void bothValuesSatisfyTheDatabaseMediaTypeConstraint() {
    // V23 constrains content_type to ^[a-z]+/[a-z0-9.+-]+$
    assertThat(OcrContentType.PLAIN).matches("^[a-z]+/[a-z0-9.+-]+$");
    assertThat(OcrContentType.MARKDOWN).matches("^[a-z]+/[a-z0-9.+-]+$");
  }
}
