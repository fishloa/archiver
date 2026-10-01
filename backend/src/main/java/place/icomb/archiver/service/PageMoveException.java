package place.icomb.archiver.service;

/** A move that was refused. The kind says which HTTP status it deserves. */
public class PageMoveException extends RuntimeException {

  public enum Kind {
    NOT_FOUND,
    BAD_REQUEST,
    CONFLICT
  }

  private final Kind kind;

  public PageMoveException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
