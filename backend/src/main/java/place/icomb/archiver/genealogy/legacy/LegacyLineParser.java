package place.icomb.archiver.genealogy.legacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import place.icomb.archiver.genealogy.GedcomDate;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Adoption;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.AdoptionKind;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Descriptor;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Event;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Marriage;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Name;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Spouse;

/**
 * Reads one line of the legacy genealogy file (genealogy.euweb.cz notation) into structured facts.
 *
 * <p>Notation: {@code *} born, {@code +} died, {@code m.}/{@code 1m:} married, {@code (div YEAR)}
 * divorced, {@code bur} buried; a spouse's own dates follow their name in parentheses. Anything the
 * notation does not settle is kept as text and reported in {@link LegacyFacts#review()}.
 */
public final class LegacyLineParser {

  private static final Pattern CODE_PREFIX = Pattern.compile("^[A-Z]\\d+\\.\\s+");
  private static final Pattern HINT_PREFIX = Pattern.compile("^\\[\\d?m\\.?\\]\\s*");
  private static final Pattern BRACKET_REMARK = Pattern.compile("\\s*\\[([^\\]]*)\\]");
  private static final Pattern MARRIAGE = Pattern.compile("^(\\d)?m[.:]\\s*(.*)$");
  private static final Pattern ALL_CHILDREN = Pattern.compile("^all children by (\\d)m\\.?$");
  private static final Pattern ADOPTED_BY = Pattern.compile("^adopted by (.+?) (\\d{4})$");
  private static final Pattern ADOPTED = Pattern.compile("^adopted (\\d{4})$");
  private static final Pattern ADOPTED_CHILD = Pattern.compile("^adopted (\\p{L}.*?) (\\d{4})$");
  private static final Pattern OTHER_SURNAME =
      Pattern.compile("^(?:adopted|assumed) surname (.+)$");
  private static final Pattern ISSUE_SURNAME = Pattern.compile("^issue bore surname (.+)$");
  private static final Pattern SAME_SPOUSE =
      Pattern.compile("^same (\\d)(?:st|nd|rd|th) (?:wife|husband)$");
  private static final Pattern DIVORCE = Pattern.compile("^div(?:\\s+(.+))?$");
  private static final Pattern ANNULMENT = Pattern.compile("^annulled(?:\\s+(.+))?$");
  private static final Pattern NICKNAME = Pattern.compile("\"([^\"]+)\"");
  private static final Pattern DATE_TOKEN =
      Pattern.compile(
          "\\d{1,2}\\.\\d{1,2}\\.\\d{4}/\\d{1,2}\\.\\d{1,2}\\.\\d{4}"
              + "|\\d{1,2}/\\d{1,2}\\.\\d{1,2}\\.\\d{4}"
              + "|\\d{1,2}\\.\\d{1,2}\\.\\d{4}(?:/\\d{2,4})?"
              + "|[IVX]+\\.\\d{4}"
              + "|\\d{4}(?:/\\d{2,4})?");
  private static final Pattern DAY_MONTH_YEAR =
      Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
  private static final Pattern ROMAN_MONTH_YEAR = Pattern.compile("([IVX]+)\\.(\\d{4})");
  private static final Pattern YEAR_SPAN = Pattern.compile("(\\d{4})/(\\d{2,4})");
  private static final Pattern YEAR = Pattern.compile("\\d{4}");
  private static final Pattern TITLE_DESCRIPTOR =
      Pattern.compile(
          "^(?:cr |Graf\\b|Reichsgraf\\b|Altgraf\\b|Freiherr\\b|Fürst\\b|Gf\\b|Frhr\\b|Fst\\b)");

  private static final List<String> MONTHS =
      List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
  private static final List<String> ROMAN =
      List.of("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII");
  private static final Set<String> QUALIFIERS = Set.of("ca", "after", "before");
  private static final List<String> IDIOMS =
      List.of(
          "on woundings after battle of Leipzig",
          "as Ursuline nun",
          "committed suicide",
          "Japanese prison",
          "car accident",
          "accident",
          "beheaded",
          "young",
          "k.a.",
          "m.i.a.");
  private static final Set<String> TITLES =
      Set.of(
          "Gf",
          "Gfn",
          "Graf",
          "Gräfin",
          "Frhr",
          "Frn",
          "Freiherr",
          "Fst",
          "Fürst",
          "Pss",
          "Pr",
          "Prinz",
          "Css",
          "Hon.",
          "Altgraf");
  private static final Set<String> PARTICLES =
      Set.of("von", "zu", "zü", "z", "ze", "de", "Des", "di", "da", "del", "dell", "van", "auf");

  private LegacyLineParser() {}

  public static LegacyFacts parse(String line) {
    List<String> notes = new ArrayList<>();
    List<String> review = new ArrayList<>();
    String rest = CODE_PREFIX.matcher(line.trim()).replaceFirst("");
    rest = HINT_PREFIX.matcher(rest).replaceFirst("");
    int continues = rest.indexOf(">>>");
    if (continues >= 0) {
      rest = rest.substring(0, continues).trim();
    }
    Matcher remark = BRACKET_REMARK.matcher(rest);
    while (remark.find()) {
      notes.add(remark.group(1));
      review.add("legacy remark: " + remark.group(1));
    }
    rest = BRACKET_REMARK.matcher(rest).replaceAll("");

    List<String> segments = splitTopLevel(rest, ';');
    Person person = person(segments.get(0), notes, review);

    List<Marriage> marriages = new ArrayList<>();
    Integer allChildrenBy = null;
    for (String segment : segments.subList(1, segments.size())) {
      String s = segment.trim();
      Matcher m;
      if ((m = MARRIAGE.matcher(s)).matches()) {
        int order = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
        marriages.add(marriage(order, m.group(2), review));
      } else if ((m = ALL_CHILDREN.matcher(s)).matches()) {
        allChildrenBy = Integer.parseInt(m.group(1));
      } else if ((m = ADOPTED_CHILD.matcher(s)).matches()) {
        person.adoptions.add(new Adoption(AdoptionKind.ADOPTED_CHILD, m.group(1), m.group(2), s));
      } else if ((m = ISSUE_SURNAME.matcher(s)).matches()) {
        notes.add(s);
      } else if (!s.isEmpty()) {
        review.add("not understood: " + s);
      }
    }
    return new LegacyFacts(
        person.nickname,
        person.birth,
        person.death,
        person.burial,
        List.copyOf(person.descriptors),
        List.copyOf(person.adoptions),
        List.copyOf(person.otherSurnames),
        List.copyOf(marriages),
        allChildrenBy,
        List.copyOf(notes),
        List.copyOf(review));
  }

  private static final class Person {
    String nickname;
    Event birth;
    Event death;
    Event burial;
    final List<Descriptor> descriptors = new ArrayList<>();
    final List<Adoption> adoptions = new ArrayList<>();
    final List<String> otherSurnames = new ArrayList<>();
  }

  private static Person person(String segment, List<String> notes, List<String> review) {
    Person p = new Person();
    int nameEnd = segment.length();
    for (int i = 0; i < segment.length(); i++) {
      char c = segment.charAt(i);
      if (c == ',' || c == '*') {
        nameEnd = i;
        break;
      }
    }
    String name = segment.substring(0, nameEnd).trim();
    Matcher quoted = NICKNAME.matcher(name);
    if (quoted.find()) {
      p.nickname = quoted.group(1);
    }
    for (String group : parenGroups(name)) {
      String inner = group.replace("\"", "").trim();
      Matcher adopted = ADOPTED.matcher(inner);
      if (adopted.matches()) {
        p.adoptions.add(new Adoption(AdoptionKind.ADOPTED, null, adopted.group(1), inner));
      } else if (p.nickname == null) {
        p.nickname = inner;
      }
    }
    if (name.toLowerCase().startsWith("a daughter") || name.toLowerCase().startsWith("a son")) {
      review.add("no name recorded: " + name);
    }

    String after = segment.substring(nameEnd);
    for (String group : parenGroups(after)) {
      notes.add(group);
      review.add("legacy remark: " + group);
    }
    after = withoutParenGroups(after);

    int star = after.indexOf('*');
    int plus = after.indexOf('+');
    int firstMarker = star < 0 ? plus : (plus < 0 ? star : Math.min(star, plus));
    String described = firstMarker < 0 ? after : after.substring(0, firstMarker);
    for (String d : splitTopLevel(described, ',')) {
      String text = d.trim();
      if (!text.isEmpty()) {
        descriptor(text, p, review);
      }
    }
    String deathText = plus < 0 ? null : after.substring(plus + 1).trim();
    if (deathText != null) {
      int bur = deathText.indexOf(", bur ");
      if (bur >= 0) {
        p.burial = new Event(null, deathText.substring(bur + 6).trim(), null);
        deathText = deathText.substring(0, bur);
      }
      p.death = event(deathText, review);
    }
    if (star >= 0) {
      String birthText = after.substring(star + 1, plus > star ? plus : after.length()).trim();
      p.birth = birthText.equals("and") ? p.death : event(stripComma(birthText), review);
    }
    return p;
  }

  private static void descriptor(String text, Person p, List<String> review) {
    Matcher m;
    if ((m = OTHER_SURNAME.matcher(text)).matches()) {
      p.otherSurnames.add(m.group(1));
    } else if ((m = ADOPTED_BY.matcher(text)).matches()) {
      p.adoptions.add(new Adoption(AdoptionKind.ADOPTED_BY, m.group(1), m.group(2), text));
    } else if ((m = ADOPTED.matcher(text)).matches()) {
      p.adoptions.add(new Adoption(AdoptionKind.ADOPTED, null, m.group(1), text));
    } else {
      p.descriptors.add(describe(text, review));
    }
  }

  private static Descriptor describe(String text, List<String> review) {
    String tag = TITLE_DESCRIPTOR.matcher(text).find() ? "TITL" : "OCCU";
    List<String> tokens = Arrays.asList(text.split("\\s+"));
    String last = tokens.get(tokens.size() - 1);
    if (tokens.size() > 1 && DATE_TOKEN.matcher(last).matches()) {
      String date = gedcom(null, last);
      if (date == null) {
        review.add("date not understood: " + text);
        return new Descriptor(tag, text, null);
      }
      return new Descriptor(tag, String.join(" ", tokens.subList(0, tokens.size() - 1)), date);
    }
    return new Descriptor(tag, text, null);
  }

  private static Marriage marriage(int order, String body, List<String> review) {
    List<String> tokens = new ArrayList<>(Arrays.asList(body.trim().split("\\s+")));
    String place = null;
    Event wedding = null;
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      boolean qualified =
          QUALIFIERS.contains(token)
              && i + 1 < tokens.size()
              && DATE_TOKEN.matcher(tokens.get(i + 1)).matches();
      if (qualified || DATE_TOKEN.matcher(token).matches()) {
        place = i == 0 ? null : stripComma(String.join(" ", tokens.subList(0, i)));
        String raw = qualified ? token + " " + tokens.get(i + 1) : token;
        String date = qualified ? gedcom(token, tokens.get(i + 1)) : gedcom(null, token);
        if (date == null) {
          review.add("date not understood: " + raw);
          wedding = new Event(firstYear(raw), place, raw);
        } else {
          wedding = new Event(date, place, null);
        }
        tokens = tokens.subList(i + (qualified ? 2 : 1), tokens.size());
        break;
      }
      if (token.matches(".*\\d.*")) {
        break;
      }
    }
    if (wedding == null) {
      wedding = new Event(null, null, null);
    }
    String rest = String.join(" ", tokens).trim();

    String endTag = null;
    Event end = null;
    if (rest.startsWith("(")) {
      int close = rest.indexOf(')');
      String inner = rest.substring(1, close).trim();
      Matcher div = DIVORCE.matcher(inner);
      Matcher annul = ANNULMENT.matcher(inner);
      if (div.matches()) {
        endTag = "DIV";
        end = div.group(1) == null ? new Event(null, null, null) : event(div.group(1), review);
        rest = rest.substring(close + 1).trim();
      } else if (annul.matches()) {
        endTag = "EVEN";
        Event when = annul.group(1) == null ? null : event(annul.group(1), review);
        end = new Event(when == null ? null : when.date(), null, "annulled");
        rest = rest.substring(close + 1).trim();
      }
    }

    Matcher same = SAME_SPOUSE.matcher(rest);
    if (same.matches()) {
      return new Marriage(order, wedding, endTag, end, Integer.parseInt(same.group(1)), null);
    }
    return new Marriage(order, wedding, endTag, end, null, spouse(rest));
  }

  private static Spouse spouse(String text) {
    List<String> review = new ArrayList<>();
    int paren = indexOfTopLevel(text, '(');
    String namePart = paren < 0 ? text : text.substring(0, paren);
    List<String> parts = splitTopLevel(namePart, ',');
    List<Descriptor> descriptors = new ArrayList<>();
    for (String extra : parts.subList(1, parts.size())) {
      if (!extra.isBlank()) {
        descriptors.add(describe(extra.trim(), review));
      }
    }
    String uncertainSurname = null;
    Event birth = null;
    Event death = null;
    for (String group : parenGroups(paren < 0 ? "" : text.substring(paren))) {
      int star = group.indexOf('*');
      int plus = group.indexOf('+');
      if (star >= 0 || plus >= 0) {
        if (plus >= 0) {
          death = event(group.substring(plus + 1), review);
        }
        if (star >= 0) {
          String b = group.substring(star + 1, plus > star ? plus : group.length());
          birth = event(stripComma(b.trim()), review);
        }
      } else if (group.endsWith("?")) {
        uncertainSurname = group.substring(0, group.length() - 1).trim();
        review.add("uncertain name: " + group);
      } else {
        review.add("legacy remark: " + group);
      }
    }
    Name name = name(parts.get(0).trim(), uncertainSurname, review);
    return new Spouse(name, birth, death, List.copyOf(descriptors), List.copyOf(review));
  }

  static Name name(String text, String uncertainSurname, List<String> review) {
    String nickname = null;
    Matcher quoted = NICKNAME.matcher(text);
    if (quoted.find()) {
      nickname = quoted.group(1);
    }
    List<String> prefix = new ArrayList<>();
    List<String> words = new ArrayList<>();
    for (String token : NICKNAME.matcher(text).replaceAll(" ").trim().split("\\s+")) {
      if (token.isEmpty()) continue;
      if (TITLES.contains(token)) {
        prefix.add(token);
      } else {
        words.add(token);
      }
    }
    String given;
    String surname;
    int particle = -1;
    for (int i = 1; i < words.size(); i++) {
      if (PARTICLES.contains(words.get(i))) {
        particle = i;
        break;
      }
    }
    if (words.isEmpty()) {
      given = null;
      surname = null;
    } else if (particle > 0) {
      given = String.join(" ", words.subList(0, particle));
      surname = String.join(" ", words.subList(particle, words.size()));
    } else if (words.size() > 1) {
      given = String.join(" ", words.subList(0, words.size() - 1));
      surname = words.get(words.size() - 1);
    } else {
      given = words.get(0);
      surname = null;
    }
    if (uncertainSurname != null && particle < 0) {
      given = words.isEmpty() ? null : String.join(" ", words);
      surname = uncertainSurname;
    } else if (uncertainSurname != null) {
      surname = surname + " " + uncertainSurname;
    }
    if (surname != null && surname.contains("/")) {
      review.add("surname uncertain: " + surname);
    }
    if ("NN".equals(given) || "N".equals(surname) || "NN".equals(surname)) {
      review.add("name not known: " + text);
    }
    return new Name(prefix.isEmpty() ? null : String.join(" ", prefix), given, surname, nickname);
  }

  static Event event(String raw, List<String> review) {
    String t = stripComma(raw.trim());
    if (t.isEmpty()) {
      return null;
    }
    if (t.endsWith("?")) {
      review.add("uncertain date: " + raw.trim());
      t = t.substring(0, t.length() - 1).trim();
    }
    String description = null;
    for (String idiom : IDIOMS) {
      if (t.equals(idiom) || t.startsWith(idiom + " ") || t.startsWith(idiom + ",")) {
        description = idiom;
        t = t.substring(idiom.length()).replaceFirst("^[,\\s]+", "");
        break;
      }
    }
    List<String> tokens = t.isEmpty() ? List.of() : Arrays.asList(t.split("\\s+"));
    String date = null;
    int dateStart = tokens.size();
    if (!tokens.isEmpty() && DATE_TOKEN.matcher(tokens.get(tokens.size() - 1)).matches()) {
      String last = tokens.get(tokens.size() - 1);
      dateStart = tokens.size() - 1;
      String qualifier = null;
      if (dateStart > 0 && QUALIFIERS.contains(tokens.get(dateStart - 1))) {
        qualifier = tokens.get(dateStart - 1);
        dateStart--;
      }
      date = gedcom(qualifier, last);
      if (date == null) {
        return unparsed(raw, review);
      }
    }
    String prefix = stripComma(String.join(" ", tokens.subList(0, dateStart)));
    if (prefix.matches(".*\\d.*")) {
      return unparsed(raw, review);
    }
    return new Event(date, prefix.isEmpty() ? null : prefix, description);
  }

  private static Event unparsed(String raw, List<String> review) {
    String text = raw.trim();
    review.add("date not understood: " + text);
    return new Event(firstYear(text), null, text);
  }

  static String gedcom(String qualifier, String token) {
    String core = null;
    Matcher m;
    if ((m = DAY_MONTH_YEAR.matcher(token)).matches()) {
      int month = Integer.parseInt(m.group(2));
      if (month >= 1 && month <= 12) {
        core = Integer.parseInt(m.group(1)) + " " + MONTHS.get(month - 1) + " " + m.group(3);
      }
    } else if ((m = ROMAN_MONTH_YEAR.matcher(token)).matches()) {
      int month = ROMAN.indexOf(m.group(1));
      if (month >= 0) {
        core = MONTHS.get(month) + " " + m.group(2);
      }
    } else if (YEAR.matcher(token).matches()) {
      core = token;
    } else if ((m = YEAR_SPAN.matcher(token)).matches() && qualifier == null) {
      String from = m.group(1);
      String to = from.substring(0, 4 - m.group(2).length()) + m.group(2);
      if (Integer.parseInt(to) > Integer.parseInt(from)) {
        core = "BET " + from + " AND " + to;
      }
    }
    if (core == null) {
      return null;
    }
    String value =
        qualifier == null
            ? core
            : switch (qualifier) {
              case "ca" -> "ABT " + core;
              case "after" -> "AFT " + core;
              default -> "BEF " + core;
            };
    try {
      GedcomDate.parse(value);
      return value;
    } catch (GedcomDate.InvalidDateException e) {
      return null;
    }
  }

  private static String firstYear(String text) {
    Matcher m = YEAR.matcher(text);
    return m.find() ? m.group() : null;
  }

  private static String stripComma(String s) {
    return s.replaceAll("[,\\s]+$", "").trim();
  }

  static List<String> splitTopLevel(String s, char separator) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') depth = Math.max(0, depth - 1);
      else if (c == separator && depth == 0) {
        out.add(s.substring(start, i));
        start = i + 1;
      }
    }
    out.add(s.substring(start));
    return out;
  }

  private static int indexOfTopLevel(String s, char target) {
    int depth = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == target && depth == 0) return i;
      if (c == '(') depth++;
      else if (c == ')') depth = Math.max(0, depth - 1);
    }
    return -1;
  }

  private static List<String> parenGroups(String s) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') {
        if (depth == 0) start = i + 1;
        depth++;
      } else if (c == ')' && depth > 0) {
        depth--;
        if (depth == 0) out.add(s.substring(start, i).trim());
      }
    }
    return out;
  }

  private static String withoutParenGroups(String s) {
    StringBuilder out = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')' && depth > 0) depth--;
      else if (depth == 0) out.append(c);
    }
    return out.toString().replaceAll("\\s+", " ").replaceAll("\\s+,", ",").trim();
  }
}
