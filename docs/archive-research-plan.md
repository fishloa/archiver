# Archive Research Plan — Section 58 Austrian Citizenship Application

## Applicant: Alexander Friedrich Josef Paul Maria Czernin
- Born: Wien, 30 April 1913
- Died: London, 5 April 2002
- Branch: CZERNIN 3 (Vinař), line C6 → D6
- Father: Pavel/Paul Czernin (*Dymokury 1879, +Krásný Dvůr 17.1.1938)
- Mother: Maria Gabriele Gfn von Orsini-Rosenberg (*Wien 1879, +Salzburg 1951)

### Siblings (all children of Pavel):
| Code | Name | Born | Died | Notes |
|------|------|------|------|-------|
| D1 | Felix Theobald Paul | Hlušice 7.3.1902 | Wien 3.1.1968 | |
| D2 | Wolfgang Otto Paul | Hlušice 4.8.1903 | Wien 10.10.1982 | |
| D3 | Anna Maria Franziska | Brno 26.5.1907 | Altaussee 25.10.1993 | |
| D4 | Franziska Paula | Hodonín 8.3.1910 | Salzburg 10.4.2000 | Twin with D5 |
| D5 | Jan Felix Jaroslav | Hodonín 8.3.1910 | Salzburg 24.5.1996 | Twin with D4 |
| D6 | **Alexander Friedrich** | **Wien 30.4.1913** | London 5.4.2002 | **Applicant** |

### Key relatives for persecution evidence:
| Person | Relation to Alexander | Persecution |
|--------|----------------------|-------------|
| Rudolf "Rudobald" Czernin (*1904) | Cousin (C1→D1) | Gestapo arrest Aug 1943, imprisoned Terezín + Gollnow |
| Humprecht Czernin (*1909, +1944) | Cousin (C1→D4) | Died in sanatorium during occupation |
| Ferdinand Czernin (*1903, +1965) | Cousin (C2→D3) | Anti-Nazi author, emigrated |
| Vera Czernin (*1904) | CZERNIN 2 branch | Married Schuschnigg, joined him in concentration camp |
| Kurt von Schuschnigg (*1897) | Married into family | Austrian Chancellor, imprisoned 1938-1945 |
| Jaromír Czernin (*1908) | CZERNIN 2 branch | Gestapo arrest 1944, forced to sell Vermeer to Hitler |
| Eugen Alfons Czernin (*1892) | Cousin (C1→D7) | Krásný Dvůr occupied by Ribbentrop |

---

## 1. PERSECUTION EVIDENCE FOUND

### 1.1 Rudolf "Rudobald" Czernin (strongest case)
- **Signed 1939 National Declaration of Czech Nobility** — 85 noblemen from 33 families declared Czech nationality as anti-Nazi resistance
- **Estate forced into Nazi administration, 1942**
- **Gestapo arrest, 11 August 1943** — charged with listening to foreign radio broadcasts
- **Imprisoned:** Kolín → Pankrác → **Terezín (Small Fortress)** → **Gollnow camp (near Stettin)**
- **Convicted March 1944**, forced labor until **liberation June 1945**
- **UN Human Rights Committee case** (Rudolf Czernin v. Czech Republic, No. 823/1998, 29 March 2005) — found violations of Articles 14 and 26 of the ICCPR

### 1.2 Jaromír Czernin (CZERNIN 2, Vermeer case)
- Wife Alix-May (granddaughter of Jewish banker Oppenheim) declared **"Jewish and an enemy of the State"** by Gestapo
- **Forced sale of Vermeer's "The Art of Painting" to Hitler** (1940, RM 1.65M) — told "Hitler would get the painting one way or another"
- **Driven from Bohemian home, 1943**
- **Arrested by Gestapo, 1944** — held in prison, forced labor, never charged

### 1.3 Vera Czernin / Schuschnigg
- **Married Kurt von Schuschnigg by proxy**, 1 June/July 1938 (while he was in Gestapo custody)
- **Voluntarily entered Sachsenhausen concentration camp** (Dec 1941) — moved into wooden house with husband and daughter
- Transferred with Schuschnigg through Flossenbürg, Dachau, liberated May 1945
- Died Kirkwood, Missouri, 18 September 1959

### 1.4 The Heydrich–Bormann Letter (16 May 1942)
- **Not yet located online** — user knows of this letter from Reinhard Heydrich to Martin Bormann describing the Czernin family as enemies of the Reich
- Date is significant: Heydrich was assassinated 27 May 1942, died 4 June 1942 — this was among his final documents
- His last report to Bormann about the Protectorate was dated **18 May 1942**
- Likely location: **Bundesarchiv R 58 (RSHA)** or **Czech National Archives Fond 1488/212 (Reichsprotektor office)**
- Academic reference: "Heydrich im Protektorat Böhmen und Mähren" (1979, Institut für Zeitgeschichte München)

### 1.5 Property confiscation (all branches)
- Dymokury, Hlušice, Krásný Dvůr, Vinoř, and other Czernin estates
- Nazi forced administration during occupation
- Post-war confiscation under **Beneš decrees** (1945-1948)
- Partial restitution after 1989 (Dymokury returned, Hlušice contested)

---

## 2. ARCHIVES INVESTIGATED

### 2.1 Arolsen Archives (International Tracing Service)
- **URL:** https://collections.arolsen-archives.org/
- **Holdings:** 40M+ documents, 17.5M people, concentration camp records, forced labor, displaced persons
- **Search:** JavaScript-rendered frontend — static fetch returns no results
- **API:** No public API documented. System uses IDEA ALM backend
- **Czernin results:** Could not query (JS required)
- **Scraping feasibility:** LOW — needs browser automation (Playwright) or reverse-engineering the JS API
- **Alternative:** USHMM staff will search ITS records for free (contact USHMM International Archives Project)
- **Recommendation:** Submit formal inquiry to Arolsen; don't build scraper

### 2.2 Bundesarchiv (German Federal Archives)
- **URL:** https://invenio.bundesarchiv.de/
- **Holdings:** 54M+ images, NSDAP records, Gestapo files (R 58), Reichsprotektor records
- **NSDAP membership (R 9361):** Staff request only, NOT publicly searchable
- **RSHA/Gestapo (R 58):** Searchable via invenio web interface, no API
- **Scraping feasibility:** LOW — no API, web-only interface
- **Recommendation:** Submit staff request for Czernin entries in R 9361 and R 58

### 2.3 Österreichisches Staatsarchiv (Austrian State Archives)
- **URL:** https://www.archivinformationssystem.at/
- **Holdings:** Vermögensverkehrsstelle (1,586 boxes), Gauakten, AdR collections
- **Czernin indexed:** YES — Rudolf Czernin (AT-OeStA/AVA Adel HAA AR 170.13), Karl Czernin (AT-OeStA/KA NL 192)
- **API:** None. Web form interface (scopeArchiv)
- **URL pattern:** `/detail.aspx?ID=NNNN`, `/volltextsuche.aspx`
- **Scraping feasibility:** MEDIUM — HTML form scraper needed
- **Recommendation:** Build scraper for search + detail pages; also direct email inquiry for Vermögensverkehrsstelle

### 2.4 Wiener Stadt- und Landesarchiv (Vienna City Archives)
- **URL:** https://www.wien.gv.at/kultur/archiv/
- **Meldezettel (residence registration):** Available on FamilySearch for 1850-1896 and 1930-1940
- **Access:** FamilySearch restricted viewing (Family History Library or limited online)
- **Scraping feasibility:** LOW — restricted access
- **Recommendation:** Request Meldezettel for Alexander Czernin (born Wien 1913) directly from archive

### 2.5 DÖW (Documentation Centre of Austrian Resistance)
- **URL:** https://www.doew.at/personensuche
- **Holdings:** 63,200+ Austrian Holocaust victims; resistance/persecution records
- **Czernin results:** None found (Czernins were not Jewish victims)
- **API:** None
- **Recommendation:** Not directly useful; skip

### 2.6 Yad Vashem
- **URL:** https://collections.yadvashem.org/en/names
- **Czernin results:** None found
- **Anti-scraping measures:** Yes
- **Recommendation:** Not useful for this case; skip

### 2.7 USHMM (US Holocaust Memorial Museum)
- **URL:** https://collections.ushmm.org/search/
- **Holdings:** Protectorate records (517 reels, 213,687 images), Vermögensverkehrsstelle microfilm, oral histories
- **Key collections:**
  - RG-48.016M: Selected records from Czech National Archives (Gestapo prison files from Pankrác, prisoner files, deportation lists)
  - RG-11.001M.23: Büro des Reichsprotektors in Böhmen und Mähren (Fond 1488)
  - Ferdinand Czernin, "Europe Going Going Gone" (1939) in library
  - Schuschnigg memoir "Ein Requiem in Rot-Weiss-Rot" (1946)
- **API:** Prototype API for Holocaust Encyclopedia only
- **Scraping feasibility:** LOW for collections; some digitized content available
- **Recommendation:** Contact USHMM reference services; request ITS search

### 2.8 Czech National Archives — badatelna.eu
- **URL:** https://badatelna.eu
- **Holdings:** Fond 212 / Fond 1488 (Reichsprotektor office), Jewish registers, conscription records
- **Protectorate records:** Online at badatelna.eu/fond/5909
- **Scraping feasibility:** HIGH — web-based, likely scrapable
- **Key fonds:**
  - Fond 212: Úřad říšského protektora v Čechách a na Moravě (Reichsprotektor office)
  - Jewish community registers (1784-1949) — fully digitized
  - Conscription records: http://digi.nacr.cz/prihlasky2
- **Recommendation:** Build scraper for Protectorate fonds; may contain the Heydrich letter

### 2.9 eBadatelna — Archive of Security Forces
- **URL:** https://ebadatelna.cz/
- **Holdings:** Security/police archives from Nazi and Communist eras
- **Feature:** OCR full-text search across archival documents
- **Scraping feasibility:** HIGH — OCR-searchable, web-based
- **Recommendation:** Search for "Czernin" across all fonds; build scraper if results found

### 2.10 findbuch.at (Austrian Victims Database)
- **URL:** https://www.findbuch.at/
- **Holdings:** 215,238 records from Austrian archives — victim/property/restitution records
- **Property notices search:** https://www.findbuch.at/property-notices.html
- **Scraping feasibility:** HIGH — web-based search
- **Recommendation:** Search for Czernin; build scraper for results

### 2.11 Matricula Online (Church Records)
- **URL:** https://data.matricula-online.eu/
- **Holdings:** Birth, marriage, death records from Austrian/German parishes up to 1938
- **Existing scraper:** `matricula-online-scraper` on PyPI + GitHub (github.com/1fge/matricula-online-scraper)
- **URL pattern:** `data.matricula-online.eu/en/[COUNTRY]/[DIOCESE]/[CHURCH]/?pg=[PAGE]`
- **Scraping feasibility:** HIGH — existing tool available
- **Recommendation:** Use for ancestry proof (Alexander's birth in Vienna parish)

### 2.12 EHRI Portal
- **URL:** https://portal.ehri-project.eu/
- **Holdings:** Metadata/descriptions from 1,800+ institutions
- **Useful for:** Finding what exists and where, not for downloading documents
- **Recommendation:** Use as finding aid, not as scrape target

### 2.13 gedenkort.at
- **URL:** https://gedenkort.at/
- **Vera Schuschnigg entry:** https://gedenkort.at/en/persons/4e657d66-3167-55f4-a332-a71a2cd4b2c3
- **Recommendation:** Reference only

### 2.14 Schuschnigg Papers (Yale / SLU)
- **Yale:** https://archives.yale.edu/repositories/12/resources/3082
- **Saint Louis University:** https://archives.slu.edu/repositories/2/resources/61 (DOC MSS 69)
- **Recommendation:** Reference for corroborating Vera Czernin's persecution; likely not scrapable

### 2.15 Lexikon Provenienzforschung
- **Jaromír Czernin entry:** https://www.lexikon-provenienzforschung.org/en/czernin-jaromir
- **Recommendation:** Reference for Vermeer forced sale evidence

---

## 3. SCRAPER SPECIFICATIONS (priority order)

### ~~Priority 1: badatelna.eu~~ → ELIMINATED
- **badatelna.eu is DISCONTINUED** (shut down ~2020)
- Replaced by **vademecum.nacr.cz** — which the archiver project already scrapes
- VadeMeCum has 2,735 fonds, 273,009 inventory records, 936,619 digitized attachments
- **Fond 212 (Reichsprotektor)** is catalogued but NOT fully digitized for online access — finding aids only
- **Action:** Search vademecum.nacr.cz for "Czernin" using existing scraper; for actual document images from Fond 212/1488, must request via USHMM (RG-11.001M.23) or visit Czech National Archives in person

---

### Priority 1: ebadatelna.cz (Archive of Security Forces)

**Why first:** OCR full-text search across police/security archives from Nazi era — can find Gestapo files, interrogation records, surveillance reports on Czernin family. Highest persecution-evidence value.

**Technology:** ASP.NET MVC + Kendo UI grid, JSON API endpoints

**Technology:** ASP.NET MVC 5.2, Kendo UI DataSource, IIS 8.5, .NET 4.0

**API Endpoints (all tested, all working):**

| Endpoint | Method | Parameters | Returns |
|----------|--------|------------|---------|
| `/Home/Item_Read` | GET | `skip`, `take`, `sort[0][field]`, `sort[0][dir]` | JSON `{Data, Total}` — root fonds |
| `/Home/Item_Read` | GET | `id={parentId}`, `skip`, `take` | JSON — children of a node |
| `/Home/Item_Read` | GET | `filter[filters][0][field]=Name&...operator=contains&...value=Czernin` | JSON — filtered results |
| `/Home/OcrFulltextRead` | POST | `searchText`, `skip`, `take` | JSON — OCR full-text search |
| `/Home/OcrFolderResult` | GET | `searchText`, `parentId`, `skip`, `take` | JSON — scoped OCR search |
| `/Home/GetBreadcrumbs` | GET | `id={nodeId}` | HTML breadcrumb trail |
| `/Home/GetImage` | GET | `id={docId}&page={pageNum}` | **Binary image data** (1-based pages) |

**Data model:** 5-level hierarchy
- Fonds (`StructureCategory: 1`) → Subdivisions (2) → Sub-collections (3) → Units (4) → Documents (`Leaf: 1`)
- Collection IDs: `s{number}` (e.g., `s1275`). Document IDs: `f{number}` (e.g., `f88433`)
- Documents have `FileCount` field = number of downloadable pages, `HasFiles` = 1/0

**JSON response format:**
```json
{"Data": [{"Id": "f88433", "Signature": "H 1-1 i.j. 1", "Name": "...",
           "Dating": "1962", "FileCount": 111, "Leaf": 1, "HasFiles": 1}],
 "Total": 1000}
```

**Scraper design:**
```
Phase 1 — Search:
  POST /Home/OcrFulltextRead  searchText="Czernin"  skip=0  take=50
  → get matching document IDs + total count
  → paginate: increment skip until all retrieved

Phase 2 — Also browse by hierarchy:
  GET /Home/Item_Read  (no id) → root fonds
  GET /Home/Item_Read?id=s{N}  → drill into collections
  → recursively map entire archive tree

Phase 3 — Download:
  For each document (Leaf=1, HasFiles=1):
    GET /Home/GetBreadcrumbs?id={docId} → archival context
    For page 1..FileCount:
      GET /Home/GetImage?id={docId}&page={n} → save binary image
```

**Authentication:** None required — entire API is public, no login needed.
**Rate limiting:** Unknown — implement 1-2s delays.
**Effort:** Medium. Clean JSON API makes this straightforward.

---

### Priority 2: findbuch.at (Austrian Victim/Property Records)

**Why second:** 215,238 records of Austrian Nazi victims — property confiscation, Aryanization, restitution. **122 Czernin results found.** Directly proves persecution for Section 58 application.

**Technology:** Contao CMS, server-rendered HTML, jQuery, PHP sessions

**Search interface:**
- **Search URL:** `POST https://www.findbuch.at/findbuch-search`
- **Form fields:** `searchterm`, `FORM_SUBMIT=mm_filter_138`, `REQUEST_TOKEN=`
- **Results URL:** `GET https://www.findbuch.at/findbuch-search/searchterm/{QUERY}`
- **Pagination:** `/findbuch-search/searchterm/{QUERY}/page/{N}`
- **"Czernin" search:** Returns **122 results**

**Record categories (with counts):**
| Category | Records | Location |
|----------|---------|----------|
| Property notices (Vermögensanmeldungen) | 52,435 | All Austria |
| Restitution files — Provincial Court Vienna | 17,207 | Vienna |
| Aryanization files | 8,542 | Vienna + regions |
| Collection Agency files | 10,206 | Vienna |
| Public Administration — Vienna | varies | Vienna |
| Restitution — Financial Directorate | varies | Vienna + regions |

**Critical limitation:** Record details require **user registration + login**. Message: "Due to data protection laws, the search results can only be viewed by registered users."

**Scraper design:**
```
1. Register account at /registration-for-individuals (one-time, manual)
2. POST login with credentials → capture PHP SESSION ID cookie
3. GET /findbuch-search/searchterm/Czernin → parse results table
4. Paginate through /page/2, /page/3, etc.
5. For each result row: follow link to detail page → extract all metadata
6. Store: name, address, DOB, file number, archive, category, asset details
```

**Authentication:** Required (free registration with ID verification)
**Anti-scraping:** CSRF tokens, session cookies — manageable with requests session
**Rate limiting:** None observed
**Effort:** Low-Medium. Standard HTML scraping, just needs authenticated session.

---

### Priority 3: archivinformationssystem.at (Austrian State Archives)

**Why third:** Has confirmed Czernin records indexed (ID 4322711). Vermögensverkehrsstelle files (1,586 boxes). State archives metadata + some digitized images.

**Technology:** ASP.NET WebForms, Telerik RadControls, ViewState postback

**URL patterns:**
| Page | URL |
|------|-----|
| Full-text search | `/volltextsuche.aspx?suchtext={QUERY}` |
| Field search | `/feldsuche.aspx` |
| Archive plan | `/archivplansuche.aspx` |
| Record detail | `/detail.aspx?ID={N}` |
| Image | `/getimage.aspx?veid={ID}&deid={deid}&sqnznr={page}&width={w}&klid={klid}` |
| PDF report | `/report.aspx?rpt=1&id={ID}` |

**Detail page metadata fields:**
- Signature (e.g., `AT-OeStA/AVA Adel HAA AR 170.13`)
- Title, date range, language, physical extent
- Archival hierarchy: Repository → Division → Fonds → Series → Item
- Digital object availability indicator
- Navigation: parent, previous, next record links

**Image access:**
- `getimage.aspx?veid={recordId}&deid={docEntityId}&sqnznr={pageNum}&width=520&klid={classId}`
- JavaScript trigger: `openimage(veid, deid, sqnznr)`
- PDF export: `report.aspx?rpt=1&id={recordId}` (ActiveReports 15)

**Scraper design:**
```
1. GET /volltextsuche.aspx → capture __VIEWSTATE, __EVENTVALIDATION
2. POST search with suchtext="Czernin" + ViewState fields
3. Parse results HTML → extract record IDs
4. For each ID:
   a. GET /detail.aspx?ID={id} → parse metadata (signature, title, dates, hierarchy)
   b. Check for digital object links
   c. If images exist: GET /getimage.aspx?veid={id}&deid=...&sqnznr=1..N
   d. Also: GET /report.aspx?rpt=1&id={id} → save PDF report
5. Follow parent/sibling links to discover related records
```

**Authentication:** None — public access
**Anti-scraping:** ViewState management required for search, but detail pages work with direct GET
**Rate limiting:** Unknown — implement 1-2s delays
**Effort:** Medium. ViewState adds complexity for search; detail pages and images are simpler.

---

### Priority 4: Matricula Online (Church Records)

**Why fourth:** Ancestry proof — Alexander's 1913 Vienna birth certificate. Lower urgency than persecution evidence but essential for the application.

**Technology:** Standard web app, JPEG images, existing PyPI scraper

**Existing scraper:** `pip install matricula-online-scraper` (v0.7.2+, Python 3.12+)

**URL patterns:**
```
/en/oesterreich/wien/                         → diocese listing (550+ parishes)
/en/oesterreich/wien/{parish}/                → parish register list
/en/oesterreich/wien/{parish}/{register-id}/  → register pages
/en/oesterreich/wien/{parish}/{register-id}/?pg={N}  → specific page
```

**Image format:** Direct JPEG files via CDN
- `http://hosted-images.matricula-online.eu/images/matricula/{encoded-path}.jpg`
- Base64-encoded paths in filenames

**CLI usage:**
```bash
# List parishes
matricula-online-scraper parish list

# Show register details
matricula-online-scraper parish show https://data.matricula-online.eu/en/oesterreich/wien/01-st-stephan/

# Download a register
matricula-online-scraper parish fetch https://data.matricula-online.eu/en/oesterreich/wien/{parish}/{register}/
```

**Target parishes for Alexander (*Wien 30.4.1913):**
- District 1: St. Stephan (cathedral), St. Peter, St. Michael
- District 3: St. Rochus, St. Othmar
- Need to check which parish the Czernin family was registered at

**Records available:** All Austrian baptism/marriage/death through 1938. April 1913 fully covered.

**Authentication:** None
**Anti-scraping:** None — robots.txt permits automated tools
**Rate limiting:** 1-3s delays recommended (no enforcement observed)
**Effort:** Low. Existing scraper handles everything.

---

### Priority 5: vademecum.nacr.cz (Czech National Archives)

**Already scraped** by the archiver project. Action items:
- Search existing scraped data for "Czernin" across all fonds
- Check Fond 212 (Reichsprotektor) finding aid entries
- Flag any digitized attachments for download
- For non-digitized Fond 212/1488 documents: request through USHMM (RG-11.001M.23, 517 reels, 213,687 images)

---

### NOT SCRAPABLE (manual requests only):

| Archive | Why not scrapable | Action |
|---------|-------------------|--------|
| **Arolsen Archives** | JS-rendered, no public API, anti-scraping | Submit formal name search request |
| **Bundesarchiv** | NSDAP (R 9361) staff-only; Gestapo (R 58) web-only | Email request for "Czernin" in R 9361 + R 58 |
| **USHMM** | No API; key collections on microfilm | Contact reference services; request ITS search |
| **FamilySearch** | Restricted viewing (Family History Library) | View Wiener Meldezettel at local FHC |
| **Yad Vashem** | Anti-scraping; no Czernin results | Skip |
| **DÖW** | No Czernin results (not Jewish victims) | Skip |

---

## 4. DOCUMENTS TO LOCATE

### Critical documents:
1. **Heydrich–Bormann letter, 16 May 1942** — Czernin family described as enemies
   - Most likely in: Bundesarchiv R 58, Czech Fond 212/1488, or USHMM RG-48.016M
2. **1939 National Declaration of Czech Nobility** — signed by Czernin family members
3. **Rudolf Czernin's Gestapo arrest file** — Pankrác prison records
4. **Rudolf Czernin's Terezín Small Fortress prisoner record**
5. **Forced administration order** for Czernin estates (1942)
6. **Beneš decree confiscation records** for Dymokury, Hlušice, Krásný Dvůr
7. **Alexander Czernin's birth certificate** from Vienna parish
8. **Vienna Meldezettel** for Alexander and family members
9. **Vermögensverkehrsstelle files** for any Czernin property in Vienna
10. **UN Human Rights Committee decision** (Czernin v. Czech Republic, No. 823/1998) — **DONE** (saved to docs/)

### Supporting documents:
11. Kurt von Schuschnigg's prisoner records (Sachsenhausen, Flossenbürg, Dachau)
12. Vera Czernin/Schuschnigg's concentration camp records
13. Jaromír Czernin's Gestapo arrest file
14. Vermeer forced sale documentation (Lexikon Provenienzforschung)
15. Ferdinand Czernin's "Europe Going Going Gone" (1939) — anti-Nazi publication

---

## 5. IMPLEMENTATION PLAN

### Phase 1 — Quick wins (existing tools)
1. Search **vademecum.nacr.cz** scraped data for "Czernin" (existing scraper)
2. Install `matricula-online-scraper`, identify correct Vienna parish, download 1913 baptism register pages

### Phase 2 — New scrapers (Python, httpx + BeautifulSoup)
3. Build **ebadatelna.cz scraper** — JSON API, OCR search for "Czernin", download document images via MediaStream
4. Register on **findbuch.at**, build authenticated scraper — download all 122 Czernin result records
5. Build **archivinformationssystem.at scraper** — ViewState search + detail page + image download

### Phase 3 — Manual requests
6. Email **Bundesarchiv** — request R 9361 + R 58 search for "Czernin"
7. Contact **USHMM** — request ITS/Arolsen search + Fond 1488 microfilm access
8. Submit **Arolsen Archives** formal name search
9. Request **Vienna Meldezettel** from Wiener Stadt- und Landesarchiv
10. Request **Vermögensverkehrsstelle** search from Austrian State Archives
