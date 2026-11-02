package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import place.icomb.archiver.genealogy.legacy.LegacyFacts;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Adoption;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.AdoptionKind;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Descriptor;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Event;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Marriage;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Name;
import place.icomb.archiver.genealogy.legacy.LegacyLineParser;

/** Lines from czernin-genealogy.txt, read into facts. Every line here is verbatim from the file. */
class LegacyLineParserTest {

  private static LegacyFacts parse(String line) {
    return LegacyLineParser.parse(line);
  }

  @Test
  void aPersonsOwnDatesAreNotTheirSpouses() {
    LegacyFacts f =
        parse(
            "D6. Alexander Friedrich Josef Paul Maria, *Wien 30.4.1913, +Oxford 5.4.2002; m."
                + " Haywards Heath 9.7.1949 Diana Zannick Hutton/Hulton (*London 12.12.1922, +Iver"
                + " Heath, Bucks 30.8.1982)");
    assertThat(f.birth()).isEqualTo(new Event("30 APR 1913", "Wien", null));
    assertThat(f.death()).isEqualTo(new Event("5 APR 2002", "Oxford", null));
    Marriage m = f.marriages().get(0);
    assertThat(m.order()).isEqualTo(1);
    assertThat(m.wedding()).isEqualTo(new Event("9 JUL 1949", "Haywards Heath", null));
    assertThat(m.spouse().name()).isEqualTo(new Name(null, "Diana Zannick", "Hutton/Hulton", null));
    assertThat(m.spouse().birth()).isEqualTo(new Event("12 DEC 1922", "London", null));
    assertThat(m.spouse().death()).isEqualTo(new Event("30 AUG 1982", "Iver Heath, Bucks", null));
    assertThat(m.spouse().review()).containsExactly("surname uncertain: Hutton/Hulton");
  }

  @Test
  void aLineWithNoDeathHasNone() {
    LegacyFacts f =
        parse(
            "D6. Alexander Friedrich Josef Paul Maria, *Wien 30.4.1913; m. Haywards Heath 9.7.1949"
                + " Diana Zannick Hutton/Hulton (*London 12.12.1922, +Iver Heath, Bucks 30.8.1982)");
    assertThat(f.death()).isNull();
  }

  @Test
  void numberedMarriagesDivorcesAndASpouseMarriedTwice() {
    LegacyFacts f =
        parse(
            "E3. Jaromír Egon Rudolf, Graf Czernin von Chudenitz und Morzin, *Praha 30.1.1908,"
                + " +Munich 1.2.1966; 1m: Budapest 23.2.1929 (div 1938) Márta Gfn Széchényi"
                + " (*15.12.1905, +Wien 24.9.1977); 2m: Starnberg 7.5.1938 (div) Alix-May von"
                + " Frankenberg (*20.9.1907, +19.12.1979); 3m: Altaussee 27.11.1944 (div) same 2nd"
                + " wife; 4m: Zürich 6.11.1951 (div 1955) Gertrud Liebl (*2.9.1922, +26.10.1998);"
                + " 5m: Salzburg 5.5.1960 Margaritha Seyffert (*Riga 5.6.1911)");
    assertThat(f.descriptors())
        .containsExactly(new Descriptor("TITL", "Graf Czernin von Chudenitz und Morzin", null));
    List<Marriage> m = f.marriages();
    assertThat(m).extracting(Marriage::order).containsExactly(1, 2, 3, 4, 5);
    assertThat(m.get(0).endTag()).isEqualTo("DIV");
    assertThat(m.get(0).end()).isEqualTo(new Event("1938", null, null));
    assertThat(m.get(0).spouse().name()).isEqualTo(new Name("Gfn", "Márta", "Széchényi", null));
    assertThat(m.get(1).end()).isEqualTo(new Event(null, null, null));
    assertThat(m.get(2).sameAsOrder()).isEqualTo(2);
    assertThat(m.get(2).spouse()).isNull();
    assertThat(m.get(2).wedding()).isEqualTo(new Event("27 NOV 1944", "Altaussee", null));
    assertThat(m.get(4).endTag()).isNull();
  }

  @Test
  void anAnnulmentIsAnEventNotADivorce() {
    Marriage m =
        parse(
                "E1. Vera Aloysia, *Munich 4.6.1904, +Kirkwood, Missouri 18.9.1959; 1m: Wien"
                    + " 23.2.1924 (annulled 1936) Gf Leopold Heinrich Fugger von Babenhausen"
                    + " (*18.7.1893 +8.7.1966); 2m: Wien 1.6.1938 Kurt von Schuschnigg, Austrian"
                    + " Chancellor (*Riva 14.12.1897, +Mutters bei Innsbruck 18.11.1977)")
            .marriages()
            .get(0);
    assertThat(m.endTag()).isEqualTo("EVEN");
    assertThat(m.end()).isEqualTo(new Event("1936", null, "annulled"));
    assertThat(m.spouse().birth()).isEqualTo(new Event("18 JUL 1893", null, null));
    assertThat(m.spouse().death()).isEqualTo(new Event("8 JUL 1966", null, null));
  }

  @Test
  void aSpouseDescriptorAfterACommaIsKeptOffTheName() {
    Marriage m =
        parse(
                "E1. Vera Aloysia, *Munich 4.6.1904; 2m: Wien 1.6.1938 Kurt von Schuschnigg,"
                    + " Austrian Chancellor (*Riva 14.12.1897, +Mutters bei Innsbruck 18.11.1977)")
            .marriages()
            .get(0);
    assertThat(m.spouse().name()).isEqualTo(new Name(null, "Kurt", "von Schuschnigg", null));
    assertThat(m.spouse().descriptors())
        .containsExactly(new Descriptor("OCCU", "Austrian Chancellor", null));
  }

  @Test
  void legacyDateNotationBecomesGedcom7() {
    assertThat(parse("C2. Jan, +ca 1599; m. Lidmila Byšická z Byšic (+1601)").death().date())
        .isEqualTo("ABT 1599");
    assertThat(parse("Humprecht Czernin z Chudenic, *1447, +after 1501").death().date())
        .isEqualTo("AFT 1501");
    assertThat(parse("D4. Humprecht Diviš, +before 1629").death().date()).isEqualTo("BEF 1629");
    assertThat(parse("D2. Jan Maximilian, +1657/64").death().date()).isEqualTo("BET 1657 AND 1664");
    assertThat(parse("H4. Václav Jan, *5.1.1724, +1731/33").death().date())
        .isEqualTo("BET 1731 AND 1733");
    assertThat(
            parse(
                    "C3. Humprecht III, cr Graf in Bohemia 8.6.1627, *IV.1570, +22.5.1632, bur"
                        + " Radenín")
                .birth()
                .date())
        .isEqualTo("APR 1570");
    assertThat(
            parse(
                    "E3. Ferdinand Karel Ottokar Děpolt Maria, *Heřmanův Městec 25.11.1903, +New York XI.1965")
                .death())
        .isEqualTo(new Event("NOV 1965", "New York", null));
  }

  @Test
  void titlesAndBurialsBecomeTheirOwnEvents() {
    LegacyFacts f =
        parse(
            "C3. Humprecht III, cr Graf in Bohemia 8.6.1627, *IV.1570, +22.5.1632, bur Radenín; m."
                + " IV.1594 Eva Polyxena Woracziczky z Pabienitz (+2.6.1632)");
    assertThat(f.descriptors())
        .containsExactly(new Descriptor("TITL", "cr Graf in Bohemia", "8 JUN 1627"));
    assertThat(f.death()).isEqualTo(new Event("22 MAY 1632", null, null));
    assertThat(f.burial()).isEqualTo(new Event(null, "Radenín", null));
    assertThat(f.marriages().get(0).wedding()).isEqualTo(new Event("APR 1594", null, null));
  }

  @Test
  void wordsThatAreNotADateGoToTheDescription() {
    assertThat(parse("E1. Bedřich, +young").death()).isEqualTo(new Event(null, null, "young"));
    assertThat(parse("I8. Walpurga, *9.2.1765, +young ca 1765").death())
        .isEqualTo(new Event("ABT 1765", null, "young"));
    assertThat(
            parse(
                    "E2. Heřman Maria Rudolf Josef, *Lysá nad Labem 12.4.1915, +m.i.a. nr Kerstch,"
                        + " Crimea 28.2.1942")
                .death())
        .isEqualTo(new Event("28 FEB 1942", "nr Kerstch, Crimea", "m.i.a."));
    assertThat(
            parse(
                    "E5. Stanislav Jan Nepomuk, *Horní Maršov 31.10.1921, +k.a. Voroniezh, Russia"
                        + " 25.7.1942")
                .death())
        .isEqualTo(new Event("25 JUL 1942", "Voroniezh, Russia", "k.a."));
    assertThat(
            parse(
                    "D2. Jan Maximilian, +1657/64; m. Eliška Johanka Švihovská z Rýzmberka (+as"
                        + " Ursuline nun, Prague 1672)")
                .marriages()
                .get(0)
                .spouse()
                .death())
        .isEqualTo(new Event("1672", "Prague", "as Ursuline nun"));
    assertThat(
            parse(
                    "I12. Petr Vojtěch Jan Nepomuk, *17.2.1769, +on woundings after battle of"
                        + " Leipzig 1796")
                .death())
        .isEqualTo(new Event("1796", null, "on woundings after battle of Leipzig"));
  }

  @Test
  void bornAndDiedTogetherIsTwoEvents() {
    LegacyFacts f = parse("F1. Jan Václav, *and +1664");
    assertThat(f.birth()).isEqualTo(new Event("1664", null, null));
    assertThat(f.death()).isEqualTo(new Event("1664", null, null));
  }

  @Test
  void anUnreadableDateKeepsItsYearAndIsFlagged() {
    LegacyFacts f = parse("A1. Gabrielle Maria Anna, *15.12.1782, +31.7./12.11.1787");
    assertThat(f.death()).isEqualTo(new Event("1787", null, "31.7./12.11.1787"));
    assertThat(f.review()).containsExactly("date not understood: 31.7./12.11.1787");

    LegacyFacts t =
        parse(
            "F2. Tomáš Zaccharius, *1651/after 1660/1666, +14.2.1700; m. 28.3.1685 Gfn Zuzana"
                + " Renata Bořitová z Martinitz (*1670, +3.2.1717)");
    assertThat(t.birth()).isEqualTo(new Event("1651", null, "1651/after 1660/1666"));
    assertThat(t.review()).containsExactly("date not understood: 1651/after 1660/1666");
  }

  @Test
  void uncertaintyAndRemarksAreKeptForReview() {
    assertThat(parse("F2. [1m.] Jan Rudolf, *ca 1670, +1713?").review())
        .containsExactly("uncertain date: 1713?");
    LegacyFacts possibly =
        parse("F3. [2m.] Josef Rudolf, *ca 1673, +Heidelberg 1713 (possibly same as F2)");
    assertThat(possibly.death()).isEqualTo(new Event("1713", "Heidelberg", null));
    assertThat(possibly.notes()).containsExactly("possibly same as F2");
    assertThat(possibly.review()).containsExactly("legacy remark: possibly same as F2");

    LegacyFacts gutstejn =
        parse("E2. Jan Heřman, +1707; m. Eliška Saloména (z Gutštejna?) (+1699)");
    assertThat(gutstejn.marriages().get(0).spouse().name())
        .isEqualTo(new Name(null, "Eliška Saloména", "z Gutštejna", null));
    assertThat(gutstejn.marriages().get(0).spouse().review())
        .containsExactly("uncertain name: z Gutštejna?");

    LegacyFacts same =
        parse(
            "G4. [2m.] František Antonín, *30.6.1710, +20.12.1739; m. 31.5.1735 Marie Isabella"
                + " Johanna Css de Merode-Westerloo (*13.10.1703, +1.4.1780) [same woman as G2's"
                + " wife]");
    assertThat(same.review()).containsExactly("legacy remark: same woman as G2's wife");
    assertThat(same.marriages().get(0).spouse().name())
        .isEqualTo(new Name("Css", "Marie Isabella Johanna", "de Merode-Westerloo", null));
  }

  @Test
  void theThreeAdoptionSpellingsAndAdoptedSurnames() {
    assertThat(
            parse(
                    "C6. František Jaromír Evžen, *Wien 3.3.1857, +Jindřichův Hradec 9.4.1932;"
                        + " adopted Eugen František Czernin-Morzin 1927")
                .adoptions())
        .containsExactly(
            new Adoption(
                AdoptionKind.ADOPTED_CHILD,
                "Eugen František Czernin-Morzin",
                "1927",
                "adopted Eugen František Czernin-Morzin 1927"));
    assertThat(
            parse(
                    "D7. Eugen Alfonsus Maria Josef, adopted by František Czernin 1927, *Praha"
                        + " 3.1.1892, +Wien 16.6.1955")
                .adoptions())
        .containsExactly(
            new Adoption(
                AdoptionKind.ADOPTED_BY,
                "František Czernin",
                "1927",
                "adopted by František Czernin 1927"));
    assertThat(
            parse(
                    "E4. Josef Maria Rudolf Ferdinand Czernin-Kinsky (adopted 1954), *Horní Maršov"
                        + " 16.6.1920")
                .adoptions())
        .containsExactly(new Adoption(AdoptionKind.ADOPTED, null, "1954", "adopted 1954"));
    assertThat(parse("F3. Stanislas Carl Maria Josef, adopted 1964, *Linz 6.8.1961").adoptions())
        .containsExactly(new Adoption(AdoptionKind.ADOPTED, null, "1964", "adopted 1964"));
    assertThat(
            parse(
                    "B2. Heřman Zdenko, adopted surname Czernin von Chudenitz und Morzin, *Wien"
                        + " 20.2.1819, +Dresden 2.8.1892")
                .otherSurnames())
        .containsExactly("Czernin von Chudenitz und Morzin");
  }

  @Test
  void childrenByOneMarriageAndNicknames() {
    LegacyFacts f =
        parse(
            "C3. Jindřich, cr Freiherr 1629, hejtman plzeňského kraje, +1640; 1m: Kryzelda"
                + " Kokorzowská z Kokorzowa (+ca 1614); 2m: Veronika Šejková z Olbramovic; all"
                + " children by 1m.");
    assertThat(f.allChildrenBy()).isEqualTo(1);
    assertThat(f.descriptors())
        .containsExactly(
            new Descriptor("TITL", "cr Freiherr", "1629"),
            new Descriptor("OCCU", "hejtman plzeňského kraje", null));
    assertThat(
            parse(
                    "D1. Rudolf Děpolt Maria Josef Ludvík (\"Rudobald\"), *Dymokury 25.8.1904, +Wien"
                        + " 16.5.1984")
                .nickname())
        .isEqualTo("Rudobald");
    assertThat(
            parse(
                    "D2. Wolfgang Otto Paul Dominikus Maria, *Hlušice 4.8.1903, +Wien 10.10.1982;"
                        + " 2m: Wien 28.4.1975 Franziska \"Sissy\" Mayer von Gunthof (*Wien 18.12.1926,"
                        + " +Wien 20.3.1987)")
                .marriages()
                .get(0)
                .spouse()
                .name())
        .isEqualTo(new Name(null, "Franziska Mayer", "von Gunthof", "Sissy"));
  }

  @Test
  void anUnnamedSpouseIsFlagged() {
    assertThat(parse("F1. Nicholas Beckett, *1963; m. NN").marriages().get(0).spouse().review())
        .containsExactly("name not known: NN");
  }

  @Test
  void aMarriageDateItCannotReadIsKeptAndFlagged() {
    LegacyFacts f =
        parse(
            "B4. Marie Antonie, *3.1.1841, +6.1.1874; m. 19/29.10.1871 Gf Ferdinand von"
                + " Fünfkirchen (*20.9.1834, +18.12.1898)");
    assertThat(f.marriages().get(0).wedding()).isEqualTo(new Event("1871", null, "19/29.10.1871"));
    assertThat(f.review()).containsExactly("date not understood: 19/29.10.1871");
  }
}
