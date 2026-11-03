package place.icomb.archiver.genealogy;

/** A refused genealogy write: the controller answers 400, 404 or 409 with {"error": message}. */
public class GenealogyException extends RuntimeException {

  public enum Kind {
    NOT_FOUND,
    BAD_REQUEST,
    CONFLICT
  }

  private final Kind kind;

  public GenealogyException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static GenealogyException notFound(String message) {
    return new GenealogyException(Kind.NOT_FOUND, message);
  }

  public static GenealogyException badRequest(String message) {
    return new GenealogyException(Kind.BAD_REQUEST, message);
  }

  public static GenealogyException conflict(String message) {
    return new GenealogyException(Kind.CONFLICT, message);
  }
}
