package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The file name is the only join between Transkribus and this archive.
 *
 * <p>Pages are uploaded as {@code rec<recordId>_seq<pageSeq>.jpg} and Transkribus preserves that in
 * the PAGE XML, so an import needs no mapping file. If this parse breaks, transcriptions land on
 * the wrong pages — which in an archive backing a legal submission is worse than not importing at
 * all.
 */
class TranskribusImportServiceTest {

  @Test
  void readsRecordAndPageOutOfTheUploadedFileName() {
    long[] parsed = TranskribusImportService.parseFileName("rec3505_seq0110.jpg");

    assertThat(parsed).containsExactly(3505L, 110L);
  }

  @Test
  void leadingZeroesOnThePageDoNotChangeTheSequence() {
    // Files are padded so they sort in page order in a folder; the padding is not part of the seq.
    assertThat(TranskribusImportService.parseFileName("rec3507_seq0001.jpg"))
        .containsExactly(3507L, 1L);
    assertThat(TranskribusImportService.parseFileName("rec4006_seq0028.jpg"))
        .containsExactly(4006L, 28L);
  }

  @Test
  void aNameTheConventionDoesNotCoverIsRefusedRatherThanGuessed() {
    // Someone uploading their own scans into the same collection must not have them written over
    // an archive page chosen by accident.
    assertThat(TranskribusImportService.parseFileName("IMG_4471.jpg")).isNull();
    assertThat(TranskribusImportService.parseFileName("page-1.jpg")).isNull();
    assertThat(TranskribusImportService.parseFileName("")).isNull();
    assertThat(TranskribusImportService.parseFileName(null)).isNull();
  }

  @Test
  void readsTheModelOutOfThePageXmlsOwnCreatorString() {
    // Provenance comes from the artefact, not from what we believe was run: this is what tells a
    // reader whether a transcription came from a free community model or from Text Titan II.
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <PcGts xmlns="http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15">
        <Metadata><Creator>prov=READ-COOP:name=TrHtr:version=2.51.0:model_id=579509:date=17_09_2026_15:32</Creator></Metadata>
        </PcGts>
        """;

    assertThat(TranskribusTrpClient.modelIdFromPageXml(xml)).isEqualTo(579509);
  }

  @Test
  void aPageXmlWithNoModelIdDoesNotInventOne() {
    assertThat(TranskribusTrpClient.modelIdFromPageXml("<PcGts/>")).isZero();
  }

  @Test
  void takesTheLinesInReadingOrderAndUnescapesThem() {
    String xml =
        """
        <PcGts><Page imageFilename="rec3505_seq0110.jpg">
          <TextRegion id="tr_1">
            <TextLine id="l1"><Coords points="1,2 3,4"/><Baseline points="1,4 3,4"/>
              <TextEquiv><Unicode>31. XII. 1943 verl&#228;ngert wurde.</Unicode></TextEquiv></TextLine>
            <TextLine id="l2"><TextEquiv><Unicode>Wien III., Metternichg. 10</Unicode></TextEquiv></TextLine>
            <TextLine id="l3"><TextEquiv><Unicode></Unicode></TextEquiv></TextLine>
          </TextRegion></Page></PcGts>
        """;

    // The blank line is dropped rather than kept as an empty line, and the text is the engine's
    // own, unaltered.
    assertThat(TranskribusTrpClient.textFromPageXml(xml))
        .isEqualTo("31. XII. 1943 verlängert wurde.\nWien III., Metternichg. 10");
  }

  @Test
  void anXmlEntityInTheTranscriptionSurvivesAsItsCharacter() {
    String xml =
        "<PcGts><TextLine><Unicode>Sch&amp;ouml; &lt;Rektorat&gt; &amp;amp;</Unicode></TextLine></PcGts>";

    assertThat(TranskribusTrpClient.textFromPageXml(xml)).contains("<Rektorat>");
  }
}
