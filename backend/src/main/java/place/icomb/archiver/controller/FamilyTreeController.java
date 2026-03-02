package place.icomb.archiver.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

  public FamilyTreeController(
      FamilyTreeService familyTreeService, PersonMatchService personMatchService) {
    this.familyTreeService = familyTreeService;
    this.personMatchService = personMatchService;
  }

  @GetMapping("/search")
  public ResponseEntity<List<Map<String, Object>>> search(
      @RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
    var results = familyTreeService.search(q, limit);
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
      map.put("pageCount", m.pageCount());
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

  @PostMapping("/reload")
  public ResponseEntity<Map<String, Object>> reload() {
    familyTreeService.reload();
    personMatchService.invalidateAll();
    return ResponseEntity.ok(Map.of("status", "ok", "count", familyTreeService.getPersonCount()));
  }

  @PostMapping("/invalidate-matches")
  public ResponseEntity<Map<String, String>> invalidateMatches() {
    personMatchService.invalidateAll();
    return ResponseEntity.ok(Map.of("status", "ok"));
  }
}
