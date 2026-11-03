package place.icomb.archiver.genealogy;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

/**
 * A GEDCOM 7 date value (FamilySearch GEDCOM 7.0, section "Date"), with the calendar bounds it
 * covers.
 *
 * <p>The bounds are derived for sorting and searching only; {@link #text()} is the fact. Gregorian
 * and Julian calendars are accepted, with their months JAN to DEC; other calendars and BCE are
 * refused.
 */
public record GedcomDate(String text, LocalDate from, LocalDate to) {

  public static final class InvalidDateException extends RuntimeException {
    public InvalidDateException(String message) {
      super(message);
    }
  }

  private static final List<String> MONTHS =
      List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

  private record Bounds(LocalDate from, LocalDate to) {}

  /** Null for a null or blank value, which GEDCOM 7 allows as an unknown date. */
  public static GedcomDate parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    List<String> t = Arrays.asList(value.trim().split("\\s+"));
    String text = String.join(" ", t);
    String head = t.get(0);
    switch (head) {
      case "FROM" -> {
        int to = t.indexOf("TO");
        if (to < 0) {
          return new GedcomDate(text, date(t.subList(1, t.size()), value).from(), null);
        }
        Bounds a = date(t.subList(1, to), value);
        Bounds b = date(t.subList(to + 1, t.size()), value);
        return ordered(text, a.from(), b.to(), value);
      }
      case "TO" -> {
        return new GedcomDate(text, null, date(t.subList(1, t.size()), value).to());
      }
      case "BET" -> {
        int and = t.indexOf("AND");
        if (and < 0) {
          throw new InvalidDateException("BET needs AND: " + value);
        }
        Bounds a = date(t.subList(1, and), value);
        Bounds b = date(t.subList(and + 1, t.size()), value);
        return ordered(text, a.from(), b.to(), value);
      }
      case "AFT" -> {
        return new GedcomDate(text, date(t.subList(1, t.size()), value).from(), null);
      }
      case "BEF" -> {
        return new GedcomDate(text, null, date(t.subList(1, t.size()), value).to());
      }
      case "ABT", "CAL", "EST" -> {
        Bounds b = date(t.subList(1, t.size()), value);
        return new GedcomDate(text, b.from(), b.to());
      }
      default -> {
        Bounds b = date(t, value);
        return new GedcomDate(text, b.from(), b.to());
      }
    }
  }

  /** The year this date is sorted under: the start of its range, else its end. */
  public Integer year() {
    if (from != null) {
      return from.getYear();
    }
    return to != null ? to.getYear() : null;
  }

  private static GedcomDate ordered(String text, LocalDate from, LocalDate to, String value) {
    if (from.isAfter(to)) {
      throw new InvalidDateException("the range ends before it starts: " + value);
    }
    return new GedcomDate(text, from, to);
  }

  private static Bounds date(List<String> tokens, String value) {
    List<String> t = tokens;
    if (!t.isEmpty() && (t.get(0).equals("GREGORIAN") || t.get(0).equals("JULIAN"))) {
      t = t.subList(1, t.size());
    }
    if (!t.isEmpty() && t.get(t.size() - 1).equals("BCE")) {
      throw new InvalidDateException("BCE dates are not supported: " + value);
    }
    if (t.isEmpty() || t.size() > 3) {
      throw new InvalidDateException("not a GEDCOM 7 date: " + value);
    }
    int year = number(t.get(t.size() - 1), value);
    if (year < 1 || year > 9999) {
      throw new InvalidDateException("year out of range: " + value);
    }
    if (t.size() == 1) {
      return new Bounds(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }
    int month = MONTHS.indexOf(t.get(t.size() - 2)) + 1;
    if (month == 0) {
      throw new InvalidDateException("unknown month in: " + value);
    }
    YearMonth ym = YearMonth.of(year, month);
    if (t.size() == 2) {
      return new Bounds(ym.atDay(1), ym.atEndOfMonth());
    }
    int day = number(t.get(0), value);
    try {
      LocalDate d = LocalDate.of(year, month, day);
      return new Bounds(d, d);
    } catch (DateTimeException e) {
      throw new InvalidDateException("no such day: " + value);
    }
  }

  private static int number(String token, String value) {
    if (!token.matches("\\d{1,4}")) {
      throw new InvalidDateException("not a GEDCOM 7 date: " + value);
    }
    return Integer.parseInt(token);
  }
}
