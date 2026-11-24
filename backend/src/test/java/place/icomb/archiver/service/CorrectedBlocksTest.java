package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CorrectedBlocksTest {

  private static final String ENGINE =
      "15. IV. 1931\n\nObecnčí úřad : Dymokurech\n\n"
          + "domorské právo panu Pavlu Černíkoví\npříslibeno ve zdejší obci jednomyslně,\n\n"
          + "Frant. Vugar,\nstarosta.";

  private static final List<String> BLOCKS =
      List.of(
          "15. IV. 1931",
          "Obecnčí úřad : Dymokurech",
          "domorské právo panu Pavlu Černíkoví\npříslibeno ve zdejší obci jednomyslně,",
          "Frant. Vugar,\nstarosta.");

  @Test
  void aCorrectedLineIsDrawnInTheBlockWhereTheMisreadingStood() {
    String now =
        ENGINE
            .replace("Obecnčí úřad : Dymokurech", "Obecní úřad v Dymokurech")
            .replace("domorské právo panu Pavlu Černíkoví", "domovské právo panu Pavlu Černínovi");

    List<String> out = CorrectedBlocks.apply(ENGINE, now, BLOCKS);

    assertThat(out)
        .containsExactly(
            "15. IV. 1931",
            "Obecní úřad v Dymokurech",
            "domovské právo panu Pavlu Černínovi\npříslibeno ve zdejší obci jednomyslně,",
            "Frant. Vugar,\nstarosta.");
  }

  @Test
  void nothingCorrectedLeavesTheBlocksAsTheEngineReadThem() {
    assertThat(CorrectedBlocks.apply(ENGINE, ENGINE, BLOCKS)).isSameAs(BLOCKS);
    assertThat(CorrectedBlocks.apply(null, ENGINE, BLOCKS)).isSameAs(BLOCKS);
    assertThat(CorrectedBlocks.apply(ENGINE, null, BLOCKS)).isSameAs(BLOCKS);
  }

  @Test
  void aCorrectionThatAddsLinesKeepsThemInTheSameBlock() {
    String now = ENGINE.replace("Frant. Vugar,", "Frant. Vugar,\n(Siegel)");

    List<String> out = CorrectedBlocks.apply(ENGINE, now, BLOCKS);

    assertThat(out.get(3)).isEqualTo("Frant. Vugar,\n(Siegel)\nstarosta.");
    assertThat(out.subList(0, 3)).isEqualTo(BLOCKS.subList(0, 3));
  }

  @Test
  void aLineTheCorrectionRemovedLeavesItsBlockEmptyThere() {
    String now = ENGINE.replace("15. IV. 1931\n\n", "");

    List<String> out = CorrectedBlocks.apply(ENGINE, now, BLOCKS);

    assertThat(out.get(0)).isBlank();
    assertThat(out.subList(1, 4)).isEqualTo(BLOCKS.subList(1, 4));
  }

  @Test
  void aBlockWhoseTextIsNotInTheEngineTextIsLeftAlone() {
    List<String> blocks = List.of("something the markdown never said", BLOCKS.get(2));
    String now = ENGINE.replace("Černíkoví", "Černínovi");

    List<String> out = CorrectedBlocks.apply(ENGINE, now, blocks);

    assertThat(out.get(0)).isEqualTo("something the markdown never said");
    assertThat(out.get(1)).contains("Černínovi");
  }
}
