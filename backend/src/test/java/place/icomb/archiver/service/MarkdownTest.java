package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Table repair.
 *
 * <p>A GFM table's width is fixed by its delimiter row, and cells beyond that width are dropped by
 * the renderer without an error. A translation model that collapses a header cell therefore deletes
 * a column of the document silently: the Terezín prisoner card for František Bořek-Dohalský came
 * back with the labels intact and every value — his birth date, his destination, the note naming
 * him a signatory of the nobility declaration — gone from the English text.
 */
class MarkdownTest {

  @Test
  void widensADelimiterRowThatIsNarrowerThanTheBody() {
    String broken =
        """
        |  Gestapo prison in the Small Fortress Terezín 1940-1945  |
        | --- |
        |  First name | František  |
        |  Surname | Bořek-Dohalský z Dohalic  |
        """;

    String repaired = Markdown.repairTables(broken);

    assertThat(repaired).contains("| --- | --- |");
    assertThat(repaired).contains("First name");
    assertThat(repaired).contains("František");
    assertThat(Markdown.plainText(Markdown.parse(repaired))).contains("Bořek-Dohalský z Dohalic");
  }

  @Test
  void leavesAWellFormedTableAlone() {
    String fine =
        """
        | Jméno | František |
        | --- | --- |
        | Povolání | diplomat a spisovatel |
        """;

    assertThat(Markdown.repairTables(fine)).isEqualTo(fine);
  }

  @Test
  void leavesTextWithoutTablesAlone() {
    String prose =
        "Geboren am 30. April 1913 zu Wien IV., Allee-Gasse 20a.\n\nGetauft am 6. Mai.\n";

    assertThat(Markdown.repairTables(prose)).isEqualTo(prose);
  }

  @Test
  void widensToTheWidestRow() {
    String broken =
        """
        | a |
        | --- |
        | b | c | d |
        """;

    String repaired = Markdown.repairTables(broken);

    assertThat(repaired).contains("| --- | --- | --- |");
    assertThat(Markdown.plainText(Markdown.parse(repaired))).contains("d");
  }

  @Test
  void keepsAlignmentMarkersWhenPadding() {
    String broken =
        """
        | a |
        | :--- |
        | b | c |
        """;

    assertThat(Markdown.repairTables(broken)).contains("| :--- | --- |");
  }

  @Test
  void ignoresEscapedPipesInsideCells() {
    String table =
        """
        | a | b |
        | --- | --- |
        | x \\| y | z |
        """;

    assertThat(Markdown.repairTables(table)).isEqualTo(table);
  }

  @Test
  void repairsTheSecondTableToo() {
    String broken =
        """
        | a |
        | --- |
        | b | c |

        text between

        | d |
        | --- |
        | e | f |
        """;

    String repaired = Markdown.repairTables(broken);

    assertThat(repaired.split("\\| --- \\| --- \\|", -1)).hasSize(3);
  }

  @Test
  void handlesNullAndBlank() {
    assertThat(Markdown.repairTables(null)).isNull();
    assertThat(Markdown.repairTables("")).isEmpty();
  }
}
