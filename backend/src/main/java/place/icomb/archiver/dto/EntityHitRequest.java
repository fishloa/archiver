package place.icomb.archiver.dto;

import java.util.List;

public record EntityHitRequest(List<EntityHitItem> entities) {

  public record EntityHitItem(
      String entityType, String value, Float confidence, Integer startOffset, Integer endOffset) {}
}
