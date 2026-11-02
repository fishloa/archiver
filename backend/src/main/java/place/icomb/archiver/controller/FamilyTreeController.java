package place.icomb.archiver.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.GenealogyReadService;
import place.icomb.archiver.genealogy.MediaImages;
import place.icomb.archiver.model.PagePersonMatch;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.FamilyTreeService.Person;
import place.icomb.archiver.service.PersonMatchService;
import place.icomb.archiver.service.PersonMatchService.RecordPersonMatch;

@RestController
@RequestMapping("/api/family-tree")
public class FamilyTreeController {

  private final FamilyTreeService familyTreeService;
  private final PersonMatchService personMatchService;
  private final GenealogyReadService genealogy;
  private final MediaImages images;

  public FamilyTreeController(
      FamilyTreeService familyTreeService,
      PersonMatchService personMatchService,
      GenealogyReadService genealogy,
      MediaImages images) {
    this.familyTreeService = familyTreeService;
    this.personMatchService = personMatchService;
    this.genealogy = genealogy;
    this.images = images;
  }

  /** A media item's image: the stored original, or its crop as lossless PNG. */
  @GetMapping("/media/{mediaId}/image")
  public ResponseEntity<?> mediaImage(@PathVariable long mediaId) {
    try {
      MediaImages.Image image = images.image(mediaId);
      return ResponseEntity.ok()
          .contentType(org.springframework.http.MediaType.parseMediaType(image.contentType()))
          .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline")
          .body(image.bytes());
    } catch (GenealogyException e) {
      return ResponseEntity.status(
              e.kind() == GenealogyException.Kind.NOT_FOUND
                  ? org.springframework.http.HttpStatus.NOT_FOUND
                  : org.springframework.http.HttpStatus.CONFLICT)
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/search")
  public ResponseEntity<List<Map<String, Object>>> search(
      @RequestParam String q,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "false") boolean all) {
    var results = familyTreeService.search(q, limit, all);
    List<Map<String, Object>> response = new ArrayList<>();
    for (var r : results) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("personId", r.personId());
      m.put("name", r.name());
      m.put("fullEntry", r.fullEntry());
      m.put("section", r.section());
      m.put("code", r.code());
      m.put("score", r.score());
      m.put("birthYear", r.birthYear());
      m.put("deathYear", r.deathYear());
      response.add(m);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping("/relate")
  public ResponseEntity<Map<String, Object>> relate(
      @RequestParam int personId, @RequestParam(required = false) Integer refId) {
    var result =
        refId != null
            ? familyTreeService.relate(personId, refId)
            : familyTreeService.relate(personId);
    if (result == null) return ResponseEntity.notFound().build();

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("personId", result.personId());
    m.put("personName", result.personName());
    m.put("kinshipLabel", result.kinshipLabel());
    m.put("pathDescription", result.pathDescription());
    m.put("commonAncestorName", result.commonAncestorName());
    m.put("commonAncestorId", result.commonAncestorId());
    m.put("stepsFromPerson", result.stepsFromPerson());
    m.put("stepsFromRef", result.stepsFromRef());
    m.put("refPersonName", result.refPersonName());
    return ResponseEntity.ok(m);
  }

  @GetMapping("/person/{id}")
  public ResponseEntity<Map<String, Object>> getPerson(@PathVariable int id) {
    Person p = familyTreeService.getPerson(id);
    if (p == null) return ResponseEntity.notFound().build();

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id);
    m.put("name", p.name);
    m.put("fullEntry", p.fullEntry);
    m.put("birthYear", p.birthYear);
    m.put("deathYear", p.deathYear);
    m.put("birthPlace", p.birthPlace);
    m.put("section", p.section);
    m.put("code", p.fullCode());
    m.put("depth", p.depth);

    if (p.parent != null) {
      m.put("parent", personRef(p.parent));
    } else {
      m.put("parent", null);
    }

    List<Map<String, Object>> children = new ArrayList<>();
    for (Person child : p.children) {
      children.add(personRef(child));
    }
    m.put("children", children);

    // Siblings = parent's other children (excluding self)
    List<Map<String, Object>> siblings = new ArrayList<>();
    if (p.parent != null) {
      for (Person sibling : p.parent.children) {
        if (sibling.id != p.id) {
          siblings.add(personRef(sibling));
        }
      }
    }
    m.put("siblings", siblings);
    m.put("spouses", p.spouses);
    m.put("events", familyTreeService.getLifeEvents(p));
    Map<String, Object> detail = genealogy.personDetail(p.id);
    if (detail != null) {
      m.putAll(detail);
    }

    return ResponseEntity.ok(m);
  }

  private static Map<String, Object> personRef(Person p) {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("id", p.id);
    ref.put("name", p.name);
    ref.put("birthYear", p.birthYear);
    ref.put("deathYear", p.deathYear);
    return ref;
  }

  @GetMapping("/page-matches/{pageId}")
  public ResponseEntity<List<Map<String, Object>>> pageMatches(@PathVariable Long pageId) {
    var matches = personMatchService.getPageMatches(pageId);
    List<Map<String, Object>> response = new ArrayList<>();
    for (PagePersonMatch m : matches) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("personId", m.getPersonId());
      map.put("personName", m.getPersonName());
      map.put("score", m.getScore());
      map.put("context", m.getContext());
      // Enrich with family tree data
      Person p = familyTreeService.getPerson(m.getPersonId());
      if (p != null) {
        map.put("section", p.section);
        map.put("code", p.fullCode());
        map.put("birthYear", p.birthYear);
        map.put("deathYear", p.deathYear);
      }
      response.add(map);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping("/record-matches/{recordId}")
  public ResponseEntity<List<Map<String, Object>>> recordMatches(@PathVariable Long recordId) {
    var matches = personMatchService.getRecordMatches(recordId);
    List<Map<String, Object>> response = new ArrayList<>();
    for (RecordPersonMatch m : matches) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("personId", m.personId());
      map.put("personName", m.personName());
      map.put("maxScore", m.maxScore());
      map.put("pageCount", m.pageSeqs().size());
      map.put("pageSeqs", m.pageSeqs());
      // Enrich with family tree data
      Person p = familyTreeService.getPerson(m.personId());
      if (p != null) {
        map.put("section", p.section);
        map.put("code", p.fullCode());
        map.put("birthYear", p.birthYear);
        map.put("deathYear", p.deathYear);
      }
      response.add(map);
    }
    return ResponseEntity.ok(response);
  }
}
