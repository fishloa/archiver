package place.icomb.archiver;

/** Shared test authentication constants. Must match application-test.yml. */
public final class TestAuth {

  public static final String PROCESSOR_TOKEN = "test-processor-token";
  public static final String PROCESSOR_AUTH_HEADER = "Bearer " + PROCESSOR_TOKEN;

  private TestAuth() {}
}
