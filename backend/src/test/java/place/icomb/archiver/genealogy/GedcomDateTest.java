package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** GEDCOM 7.0 date values (FamilySearch GEDCOM 7.0, "Date") and the bounds stored for sorting. */
class GedcomDateTest {

  private static void bounds(String value, String from, String to) {
    GedcomDate d = GedcomDate.parse(value);
    assertThat(d.from()).as(value + " from").isEqualTo(from == null ? null : LocalDate.parse(from));
    assertThat(d.to()).as(value + " to").isEqualTo(to == null ? null : LocalDate.parse(to));
  }

  @Test
  void exactAndPartialDates() {
    bounds("30 APR 1913", "1913-04-30", "1913-04-30");
    bounds("APR 1570", "1570-04-01", "1570-04-30");
    bounds("FEB 1900", "1900-02-01", "1900-02-28");
    bounds("1447", "1447-01-01", "1447-12-31");
    bounds("JULIAN 5 MAR 1580", "1580-03-05", "1580-03-05");
    bounds("GREGORIAN 1 JAN 1600", "1600-01-01", "1600-01-01");
  }

  @Test
  void rangesPeriodsAndApproximations() {
    bounds("BET 1657 AND 1664", "1657-01-01", "1664-12-31");
    bounds("AFT 1501", "1501-01-01", null);
    bounds("BEF 1629", null, "1629-12-31");
    bounds("ABT 1599", "1599-01-01", "1599-12-31");
    bounds("CAL 1700", "1700-01-01", "1700-12-31");
    bounds("EST 1700", "1700-01-01", "1700-12-31");
    bounds("FROM 1938 TO 1945", "1938-01-01", "1945-12-31");
    bounds("FROM 12 MAY 1938", "1938-05-12", null);
    bounds("TO 1945", null, "1945-12-31");
  }

  @Test
  void theYearIsTheStartOfTheRangeOrElseItsEnd() {
    assertThat(GedcomDate.parse("BET 1657 AND 1664").year()).isEqualTo(1657);
    assertThat(GedcomDate.parse("BEF 1629").year()).isEqualTo(1629);
  }

  @Test
  void anEmptyValueIsAnUnknownDate() {
    assertThat(GedcomDate.parse(null)).isNull();
    assertThat(GedcomDate.parse("  ")).isNull();
  }

  @Test
  void spacingIsNormalised() {
    assertThat(GedcomDate.parse("  ABT   1890 ").text()).isEqualTo("ABT 1890");
  }

  @Test
  void anythingElseIsRefused() {
    for (String bad :
        new String[] {
          "30.4.1913", "30 April 1913", "31 FEB 1900", "0 JAN 1900", "1 JAN 0", "abt 1890",
          "BET 1900", "BET 1900 AND", "AFT", "BET 1910 AND 1900", "1 JAN 100 BCE",
              "FRENCH_R 1 VEND 1",
          "young", "1913?", "1657/64", "ABT"
        }) {
      assertThatThrownBy(() -> GedcomDate.parse(bad))
          .as(bad)
          .isInstanceOf(GedcomDate.InvalidDateException.class);
    }
  }
}
