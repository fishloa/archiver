package place.icomb.archiver.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The text of the engine's blocks as the page reads now.
 *
 * <p>The invisible layer over a scan is positioned from the blocks the OCR engine returned, whose
 * text is what the engine read. A transcription corrected by hand afterwards ({@code
 * manual_correction}) changes {@code text_raw} but never the engine's response, which stays as the
 * record of what the engine said. Drawing the blocks as they are would put the misreading back into
 * the searchable text of every export; this carries the correction into the boxes instead.
 *
 * <p>The engine's text and the current text are aligned line by line. A line the correction left
 * alone keeps its block; a run of changed lines takes the corrected lines in the same order, so a
 * corrected line is drawn where the misread one stood.
 */
final class CorrectedBlocks {

  private CorrectedBlocks() {}

  /**
   * @param engineText the text the engine returned, from which {@code blocks} were cut
   * @param currentText the page's transcription now
   * @param blocks each block's text, in the engine's order
   * @return the blocks' text with the corrections applied; {@code blocks} unchanged when either
   *     text is missing or nothing was corrected
   */
  static List<String> apply(String engineText, String currentText, List<String> blocks) {
    if (engineText == null || currentText == null || engineText.equals(currentText)) {
      return blocks;
    }
    String[] before = engineText.split("\n", -1);
    String[] after = currentText.split("\n", -1);
    String[] now = align(before, after);

    List<String> out = new ArrayList<>(blocks.size());
    int cursor = 0;
    for (String block : blocks) {
      String[] lines = block.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (lines[i].isBlank()) {
          continue;
        }
        int at = indexOf(before, lines[i], cursor);
        if (at >= 0) {
          lines[i] = now[at];
          cursor = at + 1;
        }
      }
      out.add(String.join("\n", lines));
    }
    return out;
  }

  /** For each line of {@code before}, what stands in its place in {@code after}. */
  private static String[] align(String[] before, String[] after) {
    int n = before.length;
    int m = after.length;
    int[][] lcs = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        lcs[i][j] =
            before[i].equals(after[j])
                ? lcs[i + 1][j + 1] + 1
                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }

    String[] now = new String[n];
    String leading = null;
    int i = 0;
    int j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && before[i].equals(after[j])) {
        now[i++] = after[j++];
        continue;
      }
      int i0 = i;
      int j0 = j;
      while (i < n || j < m) {
        if (i < n && j < m && before[i].equals(after[j])) {
          break;
        }
        if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
          j++;
        } else {
          i++;
        }
      }
      String[] by = Arrays.copyOfRange(after, j0, j);
      if (i0 == i && i0 == 0) {
        leading = String.join("\n", by);
      } else if (i0 == i) {
        now[i0 - 1] = now[i0 - 1] + "\n" + String.join("\n", by);
      } else {
        replaceRun(now, i0, i, by);
      }
    }
    if (leading != null && n > 0) {
      now[0] = leading + "\n" + now[0];
    }
    return now;
  }

  /** Lines {@code from..to} of the engine's text were replaced by {@code by}. */
  private static void replaceRun(String[] now, int from, int to, String[] by) {
    int count = to - from;
    for (int k = 0; k < count; k++) {
      now[from + k] = k < by.length ? by[k] : "";
    }
    if (by.length > count) {
      now[to - 1] =
          now[to - 1] + "\n" + String.join("\n", Arrays.copyOfRange(by, count, by.length));
    }
  }

  private static int indexOf(String[] lines, String line, int from) {
    for (int k = from; k < lines.length; k++) {
      if (lines[k].equals(line)) {
        return k;
      }
    }
    return -1;
  }
}
