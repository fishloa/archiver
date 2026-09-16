package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Parsing a genealogy line.
 *
 * <p>Each line holds one person, and a married person's line carries the spouse's own dates in
 * parentheses. Those parentheses contain a {@code +}, so a death pattern applied to the whole line
 * reads the spouse's death as the person's own: Alexander Czernin, whose line records no death at
 * all, was given his wife's — 1982 — while he in fact died at Oxford in 2002.
 */
class FamilyTreeParseTest {

  private final FamilyTreeService service = new FamilyTreeService();

  @Test
  void doesNotTakeTheSpousesDeathAsTheirOwn() {
    String line =
        "D6. Alexander Friedrich Josef Paul Maria, *Wien 30.4.1913; m. Haywards Heath 9.7.1949"
            + " Diana Zannick Hutton/Hulton (*London 12.12.1922, +Iver Heath, Bucks 30.8.1982)";

    FamilyTreeService.Person p = service.parsePerson(line, "CZERNIN 3", 3, 1);

    assertThat(p.name).isEqualTo("Alexander Friedrich Josef Paul Maria");
    assertThat(p.birthYear).isEqualTo(1913);
    assertThat(p.deathYear).isNull();
  }

  @Test
  void stillReadsAPersonsOwnDeath() {
    String line =
        "D2. Wolfgang Otto Paul Dominikus Maria, *Hlušice 4.8.1903, +Wien 10.10.1982; 1m: Berlin"
            + " 6.5.1932 Alexandra von Maltitz (*Berlin 25.8.1908, +Wien 24.4.1974)";

    FamilyTreeService.Person p = service.parsePerson(line, "CZERNIN 3", 3, 2);

    assertThat(p.birthYear).isEqualTo(1903);
    assertThat(p.deathYear).isEqualTo(1982);
  }

  @Test
  void takesTheirOwnBirthPlaceNotTheSpouses() {
    String line =
        "D5. Jan Felix Jaroslav Pavel Maria, *Hodonín 8.3.1910, +Salzburg 24.5.1996; m. Wien"
            + " 30.4.1940 Osterheldis Frn von der Lippe (*Wien 28.3.1917)";

    FamilyTreeService.Person p = service.parsePerson(line, "CZERNIN 3", 3, 3);

    assertThat(p.birthPlace).contains("Hodonín");
    assertThat(p.deathYear).isEqualTo(1996);
  }

  @Test
  void masksNestedAndUnclosedParentheses() {
    assertThat(FamilyTreeService.maskParenthesised("a (b (c) d) e")).isEqualTo("a           e");
    assertThat(FamilyTreeService.maskParenthesised("a (b")).isEqualTo("a   ");
    assertThat(FamilyTreeService.maskParenthesised("a) b")).isEqualTo("a  b");
  }
}
