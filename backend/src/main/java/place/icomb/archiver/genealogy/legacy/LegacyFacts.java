package place.icomb.archiver.genealogy.legacy;

import java.util.List;

/** What one line of the legacy genealogy file says, as structured facts. */
public record LegacyFacts(
    String nickname,
    Event birth,
    Event death,
    Event burial,
    List<Descriptor> descriptors,
    List<Adoption> adoptions,
    List<String> otherSurnames,
    List<Marriage> marriages,
    Integer allChildrenBy,
    List<String> notes,
    List<String> review) {

  /** {@code date} is a GEDCOM 7 date value or null. */
  public record Event(String date, String place, String description) {}

  public record Name(String prefix, String given, String surname, String nickname) {
    public String display() {
      if (given == null) return surname;
      return surname == null ? given : given + " " + surname;
    }
  }

  /** {@code tag} is TITL or OCCU. */
  public record Descriptor(String tag, String text, String date) {}

  public enum AdoptionKind {
    ADOPTED_BY,
    ADOPTED_CHILD,
    ADOPTED
  }

  /** {@code otherName} names the adopter (ADOPTED_BY) or the adoptee (ADOPTED_CHILD). */
  public record Adoption(AdoptionKind kind, String otherName, String date, String text) {}

  public record Spouse(
      Name name, Event birth, Event death, List<Descriptor> descriptors, List<String> review) {}

  /**
   * One marriage, numbered as the line numbers it. {@code sameAsOrder} is set when the line says
   * the spouse is the one from an earlier marriage; {@code endTag} is DIV for a divorce and EVEN
   * for an annulment.
   */
  public record Marriage(
      int order, Event wedding, String endTag, Event end, Integer sameAsOrder, Spouse spouse) {}
}
