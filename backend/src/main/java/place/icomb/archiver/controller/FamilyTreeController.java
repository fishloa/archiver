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
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.FamilyTreeService.Person;

@RestController
@RequestMapping("/api/family-tree")
public class FamilyTreeController {

  private final FamilyTreeService familyTreeService;

  public FamilyTreeController(FamilyTreeService familyTreeService) {
    this.familyTreeService = familyTreeService;
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
  public ResponseEntity<Map<String, Object>> relate(@RequestParam int personId) {
    var result = familyTreeService.relate(personId);
    if (result == null) return ResponseEntity.notFound().build();

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("personId", result.personId());
    m.put("personName", result.personName());
    m.put("kinshipLabel", result.kinshipLabel());
    m.put("pathDescription", result.pathDescription());
    m.put("commonAncestorName", result.commonAncestorName());
    m.put("commonAncestorId", result.commonAncestorId());
    m.put("stepsFromPerson", result.stepsFromPerson());
    m.put("stepsFromAlexander", result.stepsFromAlexander());
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
      m.put("parent", Map.of("id", p.parent.id, "name", p.parent.name));
    } else {
      m.put("parent", null);
    }

    List<Map<String, Object>> children = new ArrayList<>();
    for (Person child : p.children) {
      children.add(Map.of("id", child.id, "name", child.name));
    }
    m.put("children", children);
    m.put("spouses", p.spouses);
    m.put("events", familyTreeService.getLifeEvents(p));

    return ResponseEntity.ok(m);
  }

  @PostMapping("/reload")
  public ResponseEntity<Map<String, Object>> reload() {
    familyTreeService.reload();
    return ResponseEntity.ok(Map.of("status", "ok", "count", familyTreeService.getPersonCount()));
  }
}
