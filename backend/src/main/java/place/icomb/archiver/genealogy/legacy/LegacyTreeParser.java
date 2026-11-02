package place.icomb.archiver.genealogy.legacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parse that {@code FamilyTreeService} ran on every startup until the tree moved into the
 * database, kept so the one-time import numbers every person exactly as the in-memory tree did.
 *
 * <p>Ids are parse-order counters over every person line, the two section roots included; the roots
 * of CZERNIN 2 and 3 repeat people already in CZERNIN 1 (I5 and I9) and are folded into them.
 */
public final class LegacyTreeParser {

  public static final class LegacyPerson {
    public int id;
    public String name;
    public String fullEntry;
    public String section;
    public String code;
    public String parentCode;
    public int depth;
    public LegacyPerson parent;
    public final List<LegacyPerson> children = new ArrayList<>();
    public Integer marriageHint;

    public String fullCode() {
      if (parentCode != null && code != null) return parentCode + "." + code;
      if (code != null) return code;
      return "";
    }
  }

  /**
   * {@code people} holds every parsed line; {@code mergedInto} maps each folded root to its twin.
   */
  public record LegacyTree(List<LegacyPerson> people, Map<Integer, Integer> mergedInto) {}

  private static final Pattern CODE_PREFIX = Pattern.compile("^([A-Z]\\d+)\\.\\s+");
  private static final Pattern HINT_PREFIX = Pattern.compile("^\\[(\\d)m\\.?\\]");
  private static final Pattern CHILDREN_OF_MARRIAGE = Pattern.compile("^---\\s*(\\d)m children");

  private LegacyTreeParser() {}

  public static LegacyTree parse(List<String> lines) {
    List<LegacyPerson> people = new ArrayList<>();
    String section = null;
    Map<Integer, LegacyPerson> depthStack = new HashMap<>();
    Map<Integer, Integer> marriageOfChildrenAtDepth = new HashMap<>();
    int nextId = 1;

    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      Matcher childrenOf = CHILDREN_OF_MARRIAGE.matcher(trimmed);
      if (childrenOf.find()) {
        marriageOfChildrenAtDepth.put(
            countLeadingSpaces(line) / 2, Integer.parseInt(childrenOf.group(1)));
        continue;
      }
      if (isSkipLine(trimmed)) continue;
      if (trimmed.startsWith("CZERNIN 1")) {
        section = "CZERNIN 1";
        depthStack.clear();
        continue;
      }
      if (trimmed.startsWith("CZERNIN 2")) {
        section = "CZERNIN 2";
        depthStack.clear();
        continue;
      }
      if (trimmed.startsWith("CZERNIN 3")) {
        section = "CZERNIN 3";
        depthStack.clear();
        continue;
      }
      if (section == null) continue;

      int depth = countLeadingSpaces(line) / 2;
      LegacyPerson p = parsePerson(trimmed, section, depth, nextId);
      if (p == null) continue;
      nextId++;
      if (p.marriageHint == null) {
        p.marriageHint = marriageOfChildrenAtDepth.get(depth);
      }

      if (depth > 0) {
        LegacyPerson parent = depthStack.get(depth - 1);
        if (parent != null) {
          p.parent = parent;
          parent.children.add(p);
          p.parentCode = parent.fullCode();
        }
      }
      depthStack.put(depth, p);
      depthStack.entrySet().removeIf(e -> e.getKey() > depth);
      marriageOfChildrenAtDepth.keySet().removeIf(d -> d > depth);
      people.add(p);
    }

    return new LegacyTree(List.copyOf(people), mergeCrossSections(people));
  }

  private static boolean isSkipLine(String trimmed) {
    return trimmed.startsWith("=")
        || trimmed.startsWith("Source:")
        || trimmed.startsWith("Notation:")
        || trimmed.startsWith("Indentation")
        || trimmed.startsWith("END OF FILE")
        || trimmed.startsWith("CZERNIN FAMILY")
        || trimmed.startsWith("---")
        || trimmed.contains("THIS IS ALEXANDER");
  }

  private static int countLeadingSpaces(String line) {
    int n = 0;
    for (int i = 0; i < line.length() && line.charAt(i) == ' '; i++) n++;
    return n;
  }

  private static LegacyPerson parsePerson(String line, String section, int depth, int id) {
    LegacyPerson p = new LegacyPerson();
    p.id = id;
    p.section = section;
    p.depth = depth;
    p.fullEntry = line;

    String rest = line;
    Matcher cm = CODE_PREFIX.matcher(rest);
    if (cm.find()) {
      p.code = cm.group(1);
      rest = rest.substring(cm.end());
    }
    if (rest.startsWith("[")) {
      Matcher hint = HINT_PREFIX.matcher(rest);
      if (hint.find()) {
        p.marriageHint = Integer.parseInt(hint.group(1));
      }
      int bracket = rest.indexOf(']');
      if (bracket > 0) {
        rest = rest.substring(bracket + 1).trim();
      }
    }

    p.name = legacyName(line);
    if (p.name.isEmpty()) return null;
    return p;
  }

  /**
   * The name the in-memory tree gave a line: after the code and any {@code [1m.]}, up to the first
   * comma, semicolon or star, without a {@code >>> CONTINUES} note.
   */
  public static String legacyName(String line) {
    String rest = CODE_PREFIX.matcher(line.trim()).replaceFirst("");
    if (rest.startsWith("[")) {
      int bracket = rest.indexOf(']');
      if (bracket > 0) {
        rest = rest.substring(bracket + 1).trim();
      }
    }
    int nameEnd = rest.length();
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == ',' || c == ';' || c == '*') {
        nameEnd = i;
        break;
      }
    }
    String name = rest.substring(0, nameEnd).trim();
    int contIdx = name.indexOf(">>>");
    return contIdx >= 0 ? name.substring(0, contIdx).trim() : name;
  }

  private static Map<Integer, Integer> mergeCrossSections(List<LegacyPerson> people) {
    LegacyPerson i5 = null, i9 = null, c2root = null, c3root = null;
    for (LegacyPerson p : people) {
      if ("CZERNIN 1".equals(p.section)) {
        if (p.fullEntry.contains(">>> CONTINUES IN CZERNIN 2")) i5 = p;
        if (p.fullEntry.contains(">>> CONTINUES IN CZERNIN 3")) i9 = p;
      }
      if ("CZERNIN 2".equals(p.section) && p.depth == 0 && p.parent == null) c2root = p;
      if ("CZERNIN 3".equals(p.section) && p.depth == 0 && p.parent == null) c3root = p;
    }
    Map<Integer, Integer> merged = new LinkedHashMap<>();
    fold(c2root, i5, merged);
    fold(c3root, i9, merged);
    return merged;
  }

  private static void fold(LegacyPerson root, LegacyPerson twin, Map<Integer, Integer> merged) {
    if (root == null || twin == null) return;
    for (LegacyPerson child : root.children) {
      child.parent = twin;
      twin.children.add(child);
    }
    merged.put(root.id, twin.id);
  }
}
