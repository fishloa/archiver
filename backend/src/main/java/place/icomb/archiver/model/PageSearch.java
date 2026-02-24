package place.icomb.archiver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

@Table("page_search")
public class PageSearch {

  @Id private Long id;
  private Long pageId;
  private String bestEngine;
  private String bestTextNorm;
  @ReadOnlyProperty private String tsv;

  public PageSearch() {}

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getPageId() {
    return pageId;
  }

  public void setPageId(Long pageId) {
    this.pageId = pageId;
  }

  public String getBestEngine() {
    return bestEngine;
  }

  public void setBestEngine(String bestEngine) {
    this.bestEngine = bestEngine;
  }

  public String getBestTextNorm() {
    return bestTextNorm;
  }

  public void setBestTextNorm(String bestTextNorm) {
    this.bestTextNorm = bestTextNorm;
  }

  public String getTsv() {
    return tsv;
  }
}
