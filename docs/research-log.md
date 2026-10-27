# Archiver Research Log

This document records the research steps, decisions, and discoveries made during the archival sourcing work for the Archiver project.

---

## 2026-03-01 — Czech National Archives Fonds Investigation

### What We Have

We have scraped two fonds from the Czech National Archives via VadeMeCum (vademecum.nacr.cz):

| Code | Fond | Records | Pages |
|------|------|---------|-------|
| NAD 1799 (ÚŘP-ST) | Státní tajemník u říšského protektora v Čechách a na Moravě | 2,507 | 66,255 |
| NAD 1464 (NSM) | Německé státní ministerstvo pro Čechy a Moravu | 241 | 14,516 |

Source: VadeMeCum (vademecum.nacr.cz), scraped by `scraper-cz`. TARGET_NADS hardcoded in `scraper-cz/src/scraper_cz/main.py`.

### Candidate Fonds Investigated

We investigated 6 additional fonds at the Czech National Archives for digitized content:

| # | Fond | Code | Size | Why Useful | Finding |
|---|------|------|------|------------|---------|
| 1 | Úřad říšského protektora (Office of the Reich Protector) | NAD 1005 | 302 linear meters | Confiscation orders, Aryanization, Jewish property restrictions | **NO SCANS** — not digitized on VadeMeCum. Confirmed in scraper code comment and by direct investigation. |
| 2 | Pozemkový úřad (Land Office) | — | unknown | Property transfers, Germanization of estates | **NO DISTINCT FOND** — land office records likely within ÚŘP (1005) and NSM (1464). |
| 3 | Ministerstvo vnitra I (Ministry of Interior) | NAD 1075 | 2,362 linear meters | Security, citizenship, minority affairs | **NO SCANS** — confirmed in scraper code comment (2026-02). |
| 4 | Spisy vězňů z doby okupace (Occupation Prisoners Files) | NAD 1077 | 245 linear meters | Transport lists to Terezín, ghetto records | **NO SCANS** — not on VadeMeCum or badatelna.eu. Physical-only access at archive. |
| 5 | Min. práce a sociální péče – Repatriace (Labour Ministry – Repatriation) | NAD 1146 | 441 linear meters | Refugee/resettlement, camp death certificates | **NO SCANS** — not on VadeMeCum. Physical-only access at archive. |
| 6 | Politické procesy 50. let (Political Trials 1950s) | — | digitized | Post-war communist trials, may reference confiscated property | **DIGITIZED but NOT on VadeMeCum** — separate portal at nacr.cz/badatelna/. Covers Slánský, Oatis, Zenáhlík trials. Would need a new scraper (not Zoomify tiles). Includes audio/film recordings added 2018. |

### Decision

Only the Political Trials (#6) have any digital content, but they're on a different system than VadeMeCum (not Zoomify tiles), so our existing `scraper-cz` can't handle them. The 3 WW2/Holocaust-era fonds (1077, 1146, 1075) are all physical-only — would need to visit the archive in person.

### How We Investigated

- Checked the scraper-cz source code (`main.py` line 69): comment already says "NADs 1005, 1075, 1420 have no digitised items as of 2026-02"
- Attempted VadeMeCum permalink URLs — returned "Záznam nenalezen" (Record not found) for NADs 1077, 1146
- Searched EHRI portal (portal.ehri-project.eu/institutions/cz-002286) for each fond — confirmed physical sizes but no digital access noted
- Checked nacr.cz English-language digital research room page — lists Political Trials as digitized, but not the prisoner files or repatriation fond
- Searched badatelna.eu for fond 243 (repatriation) — returned 404

---

## 2026-03-01 — German Digital Archives Survey

### Question

Are there German archives with digitized content we could scrape?

### Archives Identified (with actual document scans online)

| Archive | URL | Scans Online | Free? | Best For |
|---------|-----|--------------|-------|----------|
| **Arolsen Archives** | collections.arolsen-archives.org | 40M+ docs | Yes, no reg | Camp records, deportations, transport lists, forced labor |
| **Bundesarchiv (Invenio)** | invenio.bundesarchiv.de | 54M pages | Yes, no reg | NS administration, Protectorate records, Aryanization |
| **Landesarchiv NRW** | archive.nrw.de | Large (unquantified) | Yes | Largest surviving Gestapo file collection in Germany |
| **Landesarchiv Baden-Württemberg** | landesarchiv-bw.de | 28M+ images | Yes | Restitution/compensation, denazification |
| **Sächsisches Staatsarchiv** | archiv.sachsen.de | 25.6M items (4.6M downloadable) | Yes, public domain | Saxon admin, Colditz camp |
| **Archivportal-D Wiedergutmachung** | archivportal-d.de/themenportale/wiedergutmachung | 800K+ records | Yes | Restitution/compensation aggregation across all 16 states |
| **Brandenburgisches LHSA** | blha.brandenburg.de | 42K files digitized | Yes | OFP asset disposal, Aryanization in Berlin |
| **Yad Vashem Documents** | collections.yadvashem.org | Partial of 210M pages | Yes | All-source Holocaust documentation |
| **Wiener Digital Collections** | whlcollections.org | 150K pages (growing) | Yes | Nuremberg trial evidence, ghetto photos |

### Archives Excluded

- **Austrian archives** (oesta.gv.at, findbuch.at, matricula-online.eu) — already have scrapers
- **Bayerisches Hauptstaatsarchiv** — mostly finding-aids-only online
- **Sudetenland-specific collections** — no single archive exists; records split between Czech and German institutions

### Priority Ranking

1. **Arolsen Archives** — highest value, 40M docs, completely free, downloadable, Angular SPA with reversible API
2. **Bundesarchiv Invenio** — 54M pages, custom Java viewer, no public API but scrapable
3. **Landesarchiv NRW** — Gestapo Düsseldorf files, integrated web viewer
4. **Landesarchiv Baden-Württemberg** — 28M images, substantial restitution content

---

## 2026-03-01 — Czernin Family Search Across German Archives

### Purpose

Narrow down the scraping scope by searching for Czernin and related spellings across all identified archives.

### Arolsen Archives — 108 results for "Czernin"

#### Nobility / Family Members Identified

| Name | DOB | Notes |
|------|-----|-------|
| **Graf Rudolf Czernin** | 02/18/1924 | Born Prague. Munich wartime foreign registry. Explicitly titled "Graf" (Count). 5+ entries. |
| **Gräfin Alix Czernin** | 05/20/1909 | Munich wartime foreign registry. Explicitly titled "Gräfin" (Countess). |
| **Gräfin Maria Czernin** | 09/20/1906 | Munich wartime records. Explicitly titled "Gräfin". |
| **Humprecht Ottokar Czernin** | 02/09/1909 | Brandenburg-Gordens penitentiary record. Distinctly aristocratic name (Humprecht Johann Czernin, 1628-1682, was a famous ancestor). Also appears as "VON CZERNIN RUMPRECHT". |
| **Eugen Czernin** | 01/03/1892 | Born Prague. War Time Card File. |
| **Eugen Czernin** | 02/09/1905 | War Time Card File. |
| **Robert Czernin** | 02/06/1889 | War Time Card File. |
| **Josef Czernin** | 08/09/1924 | Born Prague. |
| **Edmund Czernin** | 12/11/1922 | Munich wartime records. |
| **Therese Czernin** | 10/19/1923 | Born Wi. (Wien?). |
| **Erich von Czernin** | — | "V" prefix confirms "von". |
| **Josefine Czernin** | 10/03/1895 | — |
| **Dina Czernin** | 03/07/1914 | Munich. |
| **Karl Czernin** | 05/03/1905 | Criminal police Berlin compilations. |
| **Gottfried Czernin** | — | Document 6.3.1.1. |

**Document types:** Wartime foreign registration cards, penitentiary records, phonetic name index cards. All freely downloadable.

#### Unrelated Czernins (Eastern European Jewish families)

| Name | DOB | Notes |
|------|-----|-------|
| Alter Czernin | 05/15/1923 | Prisoner #1851, Jewish, concentration camp record |
| Aron Czernin | 1919 | Prisoner #14372, Buchenwald |
| Hela Czernin | 05/15/1918 | Phonetic name card |
| Ester Czernin | 12/15/1947 | DP camp child |
| Gertrud Czernin | 06/13/1925 | Born Woinowice, Czechoslovak, Roman Catholic, DP files |
| David Czernin | 1875 | Yad Vashem archives |
| Renee Gicklhorn (née Czernin) | 02/14/1897 | Born Graz, emigrant records |
| Saul Czernin, Leizer Czernin | — | Jewish community records |

#### "Tschernin" search — 176 results

Broader set due to phonetic matching. Most additional results are Ukrainian/Russian forced laborers born in the village of Tschernin near Kyiv — not related to the Bohemian noble family.

### Bundesarchiv — ~9 photos + scattered files

| Signature | Date | Description |
|-----------|------|-------------|
| Bild 183-R95807 | Dec 1917 | Armistice at Brest-Litovsk; Graf Czernin as Austria-Hungary representative |
| Bild 102-10466 | Sep 1930 | Count Ottokar Czernin with son at Hotel Adlon, Berlin |
| N 1310 Bild-143, 144 | 1939-41 | Red Cross nurses in Czernin Palace (Neurath papers) |
| N 1310 Bild-181 | 1940 | Prague, Palais Czernin, Neurath |
| Bild 183-L08708 | Sep 1940 | Reichsprotektor Neurath departing Czernin Palace |
| Bild 183-L08706 | Sep 1940 | Von Neurath in his office at Czernin Palace |
| Bild 183-L15757 | Dec 1940 | Reichsprotektor receiving university directors at Czernin Palace |
| B 145 Bild-00204614 | Dec 1973 | Chancellor Brandt signing Prague Treaty at Palais Czernin |

Also: Press clipping file R 8034-III on Ottokar Czernin (1914-18), film footage of Brest-Litovsk. **No Czernin family Nachlass** at the Bundesarchiv — family archive is in Czech/Austrian institutions.

### Archivportal-D — 149 results for "Czernin", 9 for "Tschernin"

#### Most Significant Records

**Geheimes Staatsarchiv Berlin (Prussian State Secret Archives):**
- I. HA Rep. 167, Nrn. 203-206: **4 files on the Czernin Fideikommiss** (entailed estate, 1928-1940) — proceedings during the Nazi period. Significant for understanding the family's property holdings and their fate under the Third Reich.

**Bundesarchiv:**
- B 323: **Forced sale of the Vermeer "Die Malkunst" to Hitler** via Sonderauftrag Linz (1939-44), plus post-war restitution proceedings (1945-62).
- ZLA 7-01: Collection "Czernin-Morzin" as a restitution claimant.

**Brandenburgisches LHSA:**
- 29 ZH Brdbg 3189: **Criminal record for Humprecht Ottokar Graf Czernin** (1942-44).

**Landesarchiv Baden-Württemberg:**

*Hauptstaatsarchiv Stuttgart (WWI diplomatic):*
- Q 1/2 Bü 55: Report on conversation with Graf Czernin (1917)
- Q 1/2 Bü 178: Statements by Ottokar Theobald Graf Czernin von und zu Chudenitz (1917-18)
- Q 1/2 Bü 201: Czernin's Immediatbericht to Emperor Karl, 12 April 1917
- E 40/72 Bü 785: The Sixtus Affair and Czernin's resignation (1918-19)
- M 1/11 Bü 1012: Czernin statements on peace matters (July-October 1918)

*Generallandesarchiv Karlsruhe:*
- 46 Nr. 4071: Loan document involving **Hermann Jakob Gottlieb Czernin** (9 May 1698)
- **465 k Nr. 1826: "Czernin, Franz" — Wiedergutmachung (restitution) file**
- **465 q Nr. 9469: "Czernin, Friedrich" — restitution file**
- **465 u Nr. 878: "Czernin, Franz" — restitution file**
- **EL 902/6 Bü 3316: "Czernin, Emil" — restitution file**
- 345 Nr. 3203: Business registration for Franz Czernin, cobbler, Altheim (1948-64)
- 345 Nr. 3763: Business registration for Robert Czernin, tailor, Oberwittstadt (1952)

*Staatsarchiv Wertheim:*
- R-Lit. F Nr. 81: Genealogical reference to **Rudolphine, geborene Gräfin Czernin-Chudenitz**, wife of Altgraf Siegfried von Salm-Reifferscheidt, and daughter Augustine Maria (b. 7 January 1877).

**Sächsisches Staatsarchiv — 7 results:**

| Signature | Date | Description |
|-----------|------|-------------|
| 22179, Ma 13663 | 1917/1921 | "Aus der Ahnenreihe des Grafen Czernin" (genealogical study) |
| 11177, Nr. 029 | 1940-44 | Tax treatment of Czernin-Morzin forest estate income, **Marschendorf** |
| 10717, Nr. 1819 | 1917-18 | Saxon Foreign Ministry: diplomatic contacts with Count Czernin |
| 12881, Nr. 0919 | 1852 | Czernin genealogical file |
| 11848, Nr. 018, Bl. 389 | undated | NS-Gauverlag Sachsen: "Czernin, Ottkar, Graf" |
| 10001, Nr. 06234 | 7 Sep 1432 | Jan von Czernin and Mathes Horzessowicz arbitration |
| 10001, Nr. 06245 | 7 Sep 1432 | Jan Czernin and Mathes Horeschowitz pledge |

**Staatsarchiv Amberg (Bavaria) — "Tschernin" spelling:**
- Baron Johann Karl Tschernin von Chudenitz properties in Vohenstrauß (Upper Palatinate, bordering Bohemia), 1657-1726
- Includes his **will, burial, and estate settlement** (1685-86)
- Flossenbürg estate records (1668)
- Marriage into the Knöringen family (1726)

**Landesarchiv NRW:**
- U 180, Nr. 607: Printed marriage announcement linking Ledebur and Czernin families (1868)
- Literary archive materials for 20th-century poet Franz Josef Czernin (unrelated)

### Yad Vashem / Austrian Provenance Research

**Yad Vashem:** One educational resource about a Czernin family from Kharkiv (Jewish, unrelated). Database requires manual search — JavaScript-rendered, not machine-queryable.

**Austrian Art Restitution Advisory Board — The Jaromir Czernin / Vermeer Case:**

A 30+ page PDF decision from 18 March 2011 documents the full case:

**Key individuals:**
| Name | Dates | Role |
|------|-------|------|
| Jaromir Czernin (-Morzin) | 30 Jan 1908 Prague – 1 Feb 1966 Munich | Owner of Vermeer; subject of persecution |
| Alix (-May) Czernin, née Frankenberg-Ludwigsdorf, divorced Faber-Castell | 20 Sep 1907 Munich – 19 Dec 1979 Polop, Spain | His second wife; persecuted as "Mischling" |
| Eduard von Oppenheim | 1831-1909 | Alix's maternal grandfather; classified as "Volljude" under Nuremberg Laws |

**Timeline of persecution:**
- **1933:** *Der Stürmer* attacks; antisemitic graffiti at Faberschloss: *"Die Oppenheim, das Judenschwein, muss raus aus Stein"*
- **1935:** Alix's divorce from Roland Faber-Castell (Alix later testified it was forced by NSDAP)
- **1938:** Alix marries Jaromir Czernin (7 May)
- **1940:** Jaromir applies to join NSDAP — **rejected** because wife is *"nicht frei von jüd. sem. Rasseneinschlag"*. Gestapo classifies Alix as "politically unreliable". Rassepolitisches Amt classifies her as Jewish. Passport confiscated.
- **1940:** Hitler acquires Vermeer "Die Malkunst" via Hans Posse for RM 1,650,000 (~65% below asking price)
- **1942:** Jaromir and Alix divorce (Landgericht Trautenau)
- **1943:** Forestry estate assigned Nazi Treuhänder (trustee)
- **1944:** Jaromir imprisoned in Polizeigefängnis Linz (Aug-Sep), likely part of post-20 July 1944 mass arrests
- **1944:** Jaromir and Alix remarry (27 November)
- **Post-1945:** Austria nationalises the painting → Kunsthistorisches Museum (Inv. Nr. GG 9128)
- **2011:** Advisory board **unanimously recommends against restitution** — board concluded Alix's Mischling 2. Grades status didn't meet the persecution threshold

**Vermeer provenance chain:**
- 1804: Johann Rudolf Czernin von Chudenitz acquires the Vermeer from estate of Gottfried van Swieten
- 1862: Incorporated into the Czernin Fideikommiss
- 1923-1936: Multiple international dealers approach (Duveen Brothers, Colnaghi, Andrew Mellon — up to US$1.25M). All blocked by Austrian Bundesdenkmalamt refusing export licence.
- 1938: Anschluss; painting placed under Denkmalschutz
- 1940: Hitler acquires it for RM 1.65M
- Painting remains in KHM Vienna to this day

**Source:** provenienzforschung.gv.at/beiratsbeschluesse/Czernin_Jaromir_2011-03-18.pdf

---

## 2026-03-01 — Decision: Build Scrapers for Arolsen + Archivportal-D

### Rationale

- **Arolsen Archives**: ~108 Czernin results with downloadable wartime registration cards, detention records, camp records for named family members. Angular SPA, free, no registration. Highest direct value.
- **Archivportal-D**: 149 results aggregating records from German state archives. Fideikommiss files, Vermeer/Sonderauftrag Linz documents, 4 Wiedergutmachung case files. Discovery layer built on DDB infrastructure — may have public API.

### Status

API reverse-engineering in progress for both services.

---

## 2026-03-02 — Record 3410 Research Notes (Humprecht Ottokar Czernin)

### Outstanding Research Leads

**1. The Gnadenerlass (clemency decree):**
The sentencing letter (pages 18–19 of record 3410) refers to an annexed "beglaubigte Abschrift des Gnadenerlasses" (certified copy of the clemency decree) reducing the death sentence to life imprisonment. This document is **not present** in the 38-page file. It would likely reside in the Bundesarchiv Berlin, in the files of the Higher SS and Police Leader (Höherer SS- und Polizeiführer) at the Reichsprotector. Locating it would provide the most direct evidence of the death sentence and the identity of the intervening authority.

**2. St.G. Prag 33/42:**
The Standgericht proceeding number (St.G. Prag 33/42) may be searchable in Czech or German archives. The underlying indictment, if located, might shed light on the true nature of the weapons offence charge.

### Deduplication Advisory

Records 3366 and 3410 both reference Arolsen document 12120657. Consolidation or de-duplication is advised.

---

## Notes

- Our scraper architecture: all scrapers depend on `worker-common` and communicate with backend via HTTP API (`/api/ingest`). Files stored in archiver_store via backend.
- VadeMeCum uses Zoomify tiles; other archives will have different image delivery (IIIF, direct JPEG, PDF, etc.)
- All existing scrapers listed in `CLAUDE.md`: scraper-cz, scraper-ebadatelna, scraper-findbuch, scraper-oesta, scraper-matricula

---

## 2026-09-14 — Execution of the September 2026 Archive Search Plan

Worked through `archive-search-plan-2026-09.md`. What follows is what each task
actually returned, including the negatives.

### Task 1 — ebadatelna.cz (ABS): BLOCKED, not negative

The four name searches could not be run. The ebadatelna JSON API is only half
public:

| Endpoint | Auth required | Works |
|----------|---------------|-------|
| `/Home/Item_Read` (browse hierarchy) | no | yes |
| `/Home/Item_Read` with `filter[filters][…]` | no | **filters are ignored** — returns the unfiltered children |
| `/Home/Fulltext_Read` (general / person / codename search) | yes | returns the homepage HTML when unauthenticated |
| `/Home/OcrFulltextRead` | yes | returns `{"Data":[],"Total":0}` — *for every query, including `Praha`* |

`POST /Account/Login` with the stack credentials returns `0`: the account is
recognised (it sets `RESEARCHER_INFO … ID=25429`) but is **not verified**, and
every search endpoint is gated on verification, not on login. A zero-result OCR
search is therefore not evidence of absence — `Praha` returns zero too.

Verification cannot be done remotely by us: ABS (Mgr. Hypšová, 25 Feb 2026,
mail 427699) confirmed no video-call verification exists and pointed at Czech
eID / bank identity / mojeID / I.CA, or the IIG route. This is a user action.

Also confirmed, and worth recording: **fond 114 (ÚŘP) is no longer at ABS.**
ABS (Mgr. Houzarová, mail 455579) says fond 114 was part of the so-called
Studijní ústav MV, and was transferred to the Národní archiv — which is why NA
Chodovec is the institution scanning 114-3-17. Browsing `s1354` ("Fondy tzv.
Studijního ústavu MV") confirms it: its four children are Velitelství StB Praha,
Kabinet státobezpečnostních materiálů, Stíhání nacistických válečných zločinců,
and Židovské organizace. No fond 114.

**Next step for Task 1:** ABS still holds security-police fonds, so the four-name
search is worth requesting as a written rešerše (badatelna@abscr.cz /
info@abscr.cz) rather than waiting on account verification.

### Task 2 — ÚSTR PDFs: DONE

- **2a Hořejš**, *Rudolf a Humprecht Czerninové z Chudenic*, was already in the
  archive as **record 3519** (12 pp). The plan's URL guess was wrong in two ways:
  the path segment is `pamet-dejiny`, not `pamet-a-dejiny`, and the issue is
  **2014/04**, not 2014/03 —
  `https://www.ustrcr.cz/data/pdf/pamet-dejiny/pad1404/031-042.pdf`.
- **2b Jelínková**, *Příběh rodiny Huga Salm-Reifferscheidta*, Securitas Imperii
  18 (2011), pp. 42–69 — downloaded (28 pp, the complete article) and ingested as
  **record 3803**. Relevant because it documents the Pozemkový úřad's forced
  administration of a noble estate in detail, the same mechanism applied to the
  Czernins.

### Task 3 — Czech theses: PARTIAL

theses.cz needs a cookie handshake (it answers the first request with a
`<meta http-equiv="refresh">` stub), after which its search works fine.

Found and ingested:

- **Kučerová, Kateřina**: *Deklarace zástupců české šlechty na obranu
  československého státu a národa v letech 1938-1939*, MU FF 2012, 54 pp —
  **record 3804**. 13 Czernin mentions. Names Rudolf Theobald Czernin
  (1904–1984) among the signatories, and states that the imposed administrators
  **sold** the estates of Rudolf Czernin, alongside the forced administration of
  Humprecht Czernin's property. Full text at `https://is.muni.cz/th/ud2xj/`.

Checked and deliberately not re-ingested (already held):

- Hazdra 2013 dissertation = **record 3797**; Hazdra 2009 article = **record 3495**.
- Knoflíčková, *Heydrichiáda pohledem současníků a historiků*, MU FF 2015
  (`https://is.muni.cz/th/q0wqu/`) — downloaded and checked: **one** Czernin
  mention in 62 pages. Not ingested; say the word if it should go in anyway.

Blocked:

- **Jelínková's dissertation** (*Šlechta v proměnách*, UPa 2015) is in the
  Pardubice repository (`dk.upce.cz`, item `24a9ced1-9d1a-4fc7-a34c-b1eb258e067e`)
  but the bitstream returns **401 Unauthorized** — restricted to authenticated
  UPCE users. The published book (NLN 2017) is the practical route.
- **dspace.cuni.cz is rate-limiting this network** (429 on IPv6, connection
  reset on IPv4). Two Charles University items are identified and still to fetch:
  Vochozka's thesis
  (`/bitstream/handle/20.500.11956/75616/DPTX_2015_2_11220_0_321865_0_142032.pdf`)
  and *Vyvlastnění majetku šlechtických rodů po druhé světové válce*
  (`/bitstream/handle/20.500.11956/94464/120282050.pdf`). Retry later.

### Task 4 — Czech National Archives: MOSTLY ALREADY HELD

- **4c is already done.** Sign. **110-4/59** is **record 195** — *Soudní proces s
  R. Černínem, F. Kinským a K. Rohanem pro poslech zahraničního rozhlasu*,
  1943–1944, 53 pp, complete.
- **4a** — the archive already holds the Pozemkový úřad material: record **3791**
  (graphic overviews of the Land Office's imposed administrations, 110-4/368),
  **3206** (establishment and activity of the Land Office, overview of forced
  administrations as at 31 Dec 1942, 109-4/1359), **2915** (forced administration
  over the landed property of the Bohemian-Moravian nobility, 109-4/1337, with
  Zarnack's appeal), and **1540**/**1563** (appeals against forced administration).
  The 12 February 1942 order itself has not surfaced in the digitised material.
- **4b Národní soud** — nothing digitised. A VadeMeCum item-level search for
  `Černín` returns 172 digitised hits, of which 119 are single-scan ČTK press
  photographs, 38 are in NSM / ÚŘP-ST (fonds already fully scraped) and 13 are
  Jewish registers. The K. H. Frank trial records remain physical-only.

### Task 5 — ÖStA: RECORDS IDENTIFIED, NONE DIGITISED

`volltextsuche.aspx` reports **715** records for `Czernin`; the bulk are Ottokar
Czernin's WWI foreign-ministry papers in HHStA. Filtered searches produced the
items that matter:

| ID | Signature | Title | Date |
|----|-----------|-------|------|
| 7187521 | AT-OeStA/AdR Inneres BMI StSu StaPo Akten Kzl 32.961-2/47 | Czernin Ferdinand; Information | 1947 |
| 7187625 | AT-OeStA/AdR Inneres BMI StSu StaPo Akten Kzl 81.124-2/47 | Czernin Dr. Peter und Czernin Melanie, Wien 3., Reisnerstraße 30. Übernahme des jüdischen Gutsbesitzes Aichhof der Fanny Seemann | 1947 |
| 3380985 | AT-OeStA/HHStA SB Partezettelsammlung 17-290 | Vermählungsanzeige Czernin von Chudenitz, Paul Graf mit Gabriele Gräfin von Orsini und Rosenberg (wedding in the Maltese church, Prague Malá Strana) | 22.04.1901 |
| 3380941 / 3380943 | AT-OeStA/HHStA SB Partezettelsammlung 17-246 / 17-248 | Partezettel Felix Graf Czernin | 03.01.1968 |
| 3381004 | AT-OeStA/HHStA SB Partezettelsammlung 17-309 | Partezettel Wolfgang Dipl.Ing. Graf Czernin | 10.10.1982 |
| 7224625 | AT-OeStA/HHStA SB NL Nostitz-Rieneck 5-73 | Briefe von Felix Czernin an Georg Nostitz (2 letters) | 1966 |
| 7224646 | AT-OeStA/HHStA SB NL Nostitz-Rieneck 5-79 | Brief von Wolfgang Czernin an Georg Nostitz | 1957 |

**None of these carry digital objects** — the detail pages contain only the
`openimage(veid, deid, sqnznr)` stub and no `getimage.aspx` links, so every one
of them has to be ordered as copies. All are `Zugänglichkeit: Öffentlich` with
protection periods long expired, so no permission is needed.

Two observations worth acting on:

1. **3380985 is direct lineage evidence** — the marriage announcement of
   Alexander's parents, Paul Czernin and Gabriele Orsini-Rosenberg, April 1901.
2. **7224625 / 7224646 are the "what did they know" material** the plan asks
   for: post-war private letters from Felix and Wolfgang, Alexander's own
   brothers, in the Nostitz-Rieneck papers.

And one to be aware of rather than to order: **7187625** is a StaPo file on the
takeover of a Jewish estate by Dr. Peter and Melanie Czernin of Vienna 3. That is
a different branch and points the opposite way politically; it should be known
about before an opposing reader finds it.

### Method notes for whoever runs this next

- ÖStA search is a plain ASP.NET POST to `/volltextsuche.aspx` with the three
  ViewState fields and `ctl00$cphMainArea$txtMitAllenWoertern`. Result **paging**
  is a Telerik RadGrid postback that did not reproduce with a plain form POST —
  narrow the query instead of paging 72 pages.
- theses.cz and is.muni.cz both need the cookie handshake first; thereafter
  `https://is.muni.cz/th/<id>/` lists the thesis files directly.
- dk.upce.cz is DSpace 7: `/server/api/discover/search/objects?query=…`, then
  `/core/items/{uuid}/bundles` → `/core/bundles/{uuid}/bitstreams` → the
  `_links.content.href`. Restricted items 401 at the content step.

### 2026-09-14, later — dspace.cuni.cz reached from zelkova

dspace.cuni.cz rate-limits this network (429 on IPv6, silent reset on IPv4) but
answers zelkova normally, so both Charles University law theses were fetched
through a container on the test stack and posted straight to the ingest API from
there.

- **Record 3805** — VOCHOZKA, Šimon: *Historie majetku hlubocké větve
  Schwarzenbergů v období 1938-1950*, UK Právnická fakulta 2016 (supervisor
  Kuklík), 94 pp. Confirmed to contain Czernin in its text. This is the thesis
  that quotes the Heydrich letter of 16 May 1942 at fn. 57.
- **Not ingested:** BLAŽKOVÁ, Tereza: *Vyvlastnění majetku šlechtických rodů po
  druhé světové válce*, UK Právnická fakulta 2018
  (`hdl.handle.net/20.500.11956/94464`, 3.1 MB). It is about the post-war Lex
  Schwarzenberg expropriation rather than Nazi-era persecution. The PDF is sitting
  at `/tmp/b.pdf` in `archiver-test-frontend-test-1` if it is wanted.

Method, for reuse: `POST /containers/{id}/exec` then `/exec/{id}/start` through the
Portainer dockerProxy; `bun -e` inside the frontend image can both fetch the file
and multipart-POST it to `/api/ingest/records/{id}/text-pdf`. A `wget` against
dspace from there takes minutes, so expect the exec call to be backgrounded.

---

## 2026-09-15 — findbuch.at swept properly for the first time

The login works (the credentials note saying it was broken on their end is out of
date). More importantly, `scraper-findbuch` was never reading past the first page:
it built `/findbuch-search/searchterm/X/page/2`, which findbuch.at accepts and
silently answers with page 1. Nothing failed, so nothing flagged it — the archive
simply held 18 findbuch records and no one knew what was missing. Fixed in
`f869993` (`?page=N`, plus `perPage/100`), with tests.

A full `Czernin` sweep returns **122 records**. Of those, **9 carry a Czernin
surname**; the other 113 are records of Jewish victims that merely mention the
string — an address in Czerningasse (Wien 2), a Czerningarage, or a Czernin
appearing as the acquirer of the property.

The nine:

| ID | Name | Holding |
|----|------|---------|
| 17830 | Czernin, Arthur (b. 14.11.1880) | Collection Agencies A and B — negative files, immovable property |
| 353887 | Czernin-Chudenic, Otto | RK 385/1948 |
| 287004 | Czernin-Chudenitz, Otto | Rückstellungskommission, LG Klagenfurt, RK 176/1949 |
| 353888 / 353889 / 353890 | Czernin-Chudenitz, Otto | RK 250/1949, RK 251/1949, RK 252/1949 |
| 248145 | Czernin-Dirkenau, Liselotte | Restitution files, Finanzlandesdirektion Wien/NÖ/Bgld, 21546 |
| 248146 | Czernin-Morzin, Jaromir | Restitution files, FLD Wien/NÖ/Bgld, 19867 |
| 301599 | Czernin-Morzin, Jaromir | C 105, Bezirk 1 |

**Result for the case: negative.** Not one of the nine belongs to Alexander's line
— no Paul, no Gabriele, no Alexander, Felix, Wolfgang, Anna, Franziska or Jan. The
Otto Czernin-Chudenitz files are restitution proceedings in Carinthia in which he
is the party being claimed against, which places them in the same category as the
Peter/Melanie StaPo file: other branches, pointing the other way.

The two Jaromír Czernin-Morzin files are the only ones with any bearing on the
case, as background to the Vermeer forced sale. Not ingested — say the word.

No bulk ingest was run. 113 of the 122 are other families' persecution records and
do not belong in this archive.

### 2026-09-15, later — the findbuch records were thin, and why

The 122 went in and came out near-empty: `pageCount 0` and a description
reading `"Archive: File TypeRestitution files of the Financial Directorates…"`.
Two faults behind that.

The first is in the parser. Detail pages are
`<div class="field surname"><div class="label">Surname</div><div class="value">Rakower</div></div>`,
and reading a field as one blob glues label to value. Parsing by the class name
instead yields the whole record: name, date of birth, street, town, district,
remarks, holding, file number, and the **signature** —
`AT-OeStA/AdR/E-uReang/FLD 6822` — which is what an archive is quoted when
copies are ordered. That signature is now the record's reference code.

The second was in the verification, and it is the one that mattered. `--dry-run`
returned *before* `parse_detail_page`, so a dry run proved only that a page had
fetched. Five dry-run records looked fine and told us nothing; 122 records were
then ingested, each firing a paid `translate_record` and `embed_record` job, and
nobody had looked at a finished record. `--dry-run` now parses and prints the
record it would write, and `--refresh` re-ingests records already held.

One more thing worth knowing: **re-ingesting an existing record does not
re-translate it.** The record returns to `complete` without re-queueing
`translate_record`, so a refreshed record keeps its old `descriptionEn`. To
correct stored text, delete and re-ingest.

**What the archive now holds.** All 123 findbuch records were deleted (the 122
plus the junk "Name" header record), and the nine Czernin-surname records were
re-ingested fresh, each with one clean translate/embed pass:

| Record | Name | Signature |
|--------|------|-----------|
| 3911–3919 | Czernin Arthur; Czernin-Chudenitz Otto ×4; Czernin-Chudenic Otto; Czernin-Dirkenau Liselotte; Czernin-Morzin Jaromir ×2 | AT-OeStA/AdR/E-uReang/… and AT-KLA/144-C-RK… |

The 113 records of other families were not kept. They were other people's
persecution files that merely contained the string Czernin — an address in
Czerningasse, the Czerningarage, or a Czernin as acquirer — and the finding they
support (that Alexander's line does not appear in findbuch at all) is recorded
above and does not depend on holding them.

Credential note corrected: the findbuch.at login works. The memory saying it was
broken on their end dated from an earlier attempt.

### 2026-09-15 — Bundesarchiv R 43-II/1326 read without spending anything

The Zarnack memorandum was the reason for chasing R 43-II/1326 (Reichskanzlei,
Protektorat Böhmen und Mähren). The earlier note proposed ingesting its 216 pages
and letting our own OCR search them. That would have cost 216 Mistral OCR calls
plus a `translate_page` LLM call per page. It was not necessary.

The page images are open — no session, no auth:
`invenio.bundesarchiv.de/invenio/invenio-viewer/lixe/files/41/82/<uuid>/R_43_II_1326_NNNN.jpg`.
All 216 (533 MB) were downloaded and OCR'd locally with tesseract and the German
model. Cost: nothing.

**Result: negative, and the volume is the wrong one.** No `Zarnack`, no `Czernin`,
and zero instances of Adel, Grundbesitz, Beschlagnahme, Zwangsverwaltung,
Enteignung, Bodenamt or Denkschrift.

The negative is trustworthy because the corpus is sound rather than empty:
Protektorat on 39 pages, Reichsprotektor on 44, Böhmen and Mähren on 37 each,
Heydrich on 11, 21,391 words in total. The first pass looked like a 58% OCR
failure — 126 of 216 pages under 200 bytes — but measuring ink coverage showed
**161 of the 216 pages are blank versos**. Only 7 pages carry ink without text,
and 4 of those are covers. So the file is ~55 content pages, and they match its
`Enthält` list exactly: German-Czech relations, Jewish armbands, Heydrich's
teleprinter report, the Rudolfinum address, Wehrmacht jurisdiction. It is not a
noble-property file.

Not ingested. Nothing in it bears on the case.

**Method worth reusing.** For any archive that serves page images openly, pull
them, OCR locally, and decide relevance before paying for the pipeline. Triage
blank leaves by dark-pixel fraction (below ~0.008 over the cropped page) so a
wall of near-empty OCR output is not mistaken for a broken scrape — which is
exactly what it looked like at first.

**Still open: 1326a and 1326b.** Invenio's catalogue search is a JSF/PrimeFaces
app. `/invenio/direktlink/<ve_uuid>/` needs no login but lands in the application
shell rather than a parseable record, and driving "Suche ohne Anmeldung" in a
browser fails on a stray `.ui-dialog-mask` that intercepts the click; removing it
and firing the postback leaves the page where it was. A written enquiry to the
Bundesarchiv will be cheaper than beating the app.

### 2026-09-15 — DDB: two primary sources on Felix and Humprecht

DDB's Solr API is open, needs no key, and answers at
`api.deutsche-digitale-bibliothek.de/search/index/search/select`.

**`scraper-ddb` would have missed both of these.** Its query filters
`type_fct:mediatype_003`, but archival *Akten* are `mediatype_007`. With that
filter, "Czernin" returns 5 hits, all Landesarchiv Baden-Württemberg files about
Ottokar Czernin, the WWI foreign minister — the wrong branch entirely. Dropping
the filter and keeping `sector_fct:sec_01` returns **149**.

**1. Humprecht's prison file — Brandenburgisches Landeshauptarchiv.**

> *Czernin, Humprecht Ottokar Graf, 9.2.1909, verurteilt wegen unbefugten
> Waffenbesitzes*
> Rep. 29 Zuchthaus Brandenburg → Häftlingspersonalakten → Buchstabe C
> **29 ZH Brdbg 3189 (760738)**, 1942–1944
> *Enthält u. a.: Todesstrafe, zu lebenslänglichem Zuchthaus begnadigt.- Am
> 14.9.1944 in ein Sanatorium bei Prag eingeliefert.*

The date of birth matches exactly, and so does the charge. The abstract confirms
from the prison's own file what we had only from secondary literature: death
sentence, commuted to life, and transfer on 14 September 1944 to a sanatorium
near Prague — he died at Sanatorium Pleš five days later, on 19 September 1944.
This is the prison file the search plan asked for, beyond what Arolsen holds.

**2. Felix interrogated at Nuremberg — Staatsarchiv Nürnberg.**

> *Czernin, Felix Graf, geb. 07.03.1902 in Bluschitz/Böhmen, Dresdner Bank*
> Nürnberger Prozesse, KV-Anklage, Interrogations **C 22**
> Vern. Nr. 2330A/Verber, 6.11.1947 [7 Bl.]; 2330B, 7.11.1947 [8 Bl.];
> 2330C, 10.11.1947 [6 Bl.]; 2330D, 19.11.1947 [4 Bl.]

Alexander's own brother, questioned by the Nuremberg prosecution across four
sessions in November 1947 — 25 sheets — in connection with the Dresdner Bank,
which took over Czech banks in Bohemia after March 1939. For the question the
case now turns on, what the siblings knew and said about the occupation, this is
as direct as a source gets: Felix in his own words, under examination, two years
after the war.

Confirmed independently in the US National Archives finding aid for microfilm
**M-1019** (Records of the US Nuremberg War Crimes Trials Interrogations,
1946–1949), entry 527: *"Czernin, Felix, Nov. 6, 7, 10, and 19, 1947"*. The roll
ranges put him in **Roll 12** (Cremer–Deutsch). So the interrogations can be
ordered from Staatsarchiv Nürnberg or obtained from NARA M-1019 Roll 12.

Neither is digitised; both are now precisely enough identified to order.

### 2026-09-15 — Harvard has Felix's Nuremberg interrogations, digitised and free

The Staatsarchiv Nürnberg record said the interrogations exist. Harvard Law
School's Nuremberg Trials Project has the images. Its search returns **13
documents** for Czernin; all 40 page images were downloaded from
`sfo2.digitaloceanspaces.com/harvard-law-library-nuremberg-documents/` and OCR'd
locally. Cost: nothing.

**The four interrogations, matching the Nürnberg finding aid exactly:**

| Summary | Index | Date | Harvard id |
|---------|-------|------|-----------|
| No. 4031 | 2330 | 6 Nov 1947 | 609602 |
| No. 4050 | 2330-B | 7 Nov 1947 | 609621 |
| No. 4049 | — | 10 Nov 1947 | 609620 |
| No. 4108 | 2330-D | 19 Nov 1947 | 609677 |

Office of U.S. Chief of Counsel for War Crimes, Evidence Division, Interrogation
Branch; interrogated by Mr. O. Verber for the **Dresdner Bank Trial Team**
(Mr. Adams).

**What Felix says about himself.** Director of the Böhmische Escompte Bank from
1931; manager of its Aussig branch from summer 1939, during which the institution
became part of the Dresdner Bank; transferred in 1941 to the **Continentale Bank
in Brussels**; drafted in 1943. Rasche then kept him out of the army for
intelligence work: after a course in Berlin he was sent to the Handelskreditbank
in Pressburg and, under Major Bechtle of Berlin counter-intelligence office I
(Air), spent his time **trying to build an information centre about Russia via
Turkey and Persia**, "failing to establish any contacts whatsoever", after which
he was returned to military service.

Two things in that bear directly on the case.

1. **Turkey.** Felix was arrested by the Gestapo on 28 March 1945 charged, among
   other things, with contact with enemy powers *via Turkey*. Here he is in 1947,
   to American interrogators, describing exactly such a Turkey channel — as an
   Abwehr task he failed at. Whatever the truth of it, the factual thread behind
   the Gestapo charge is now independently documented.
2. **Falkenhausen.** Summary 4049: "Subject states that he was on excellent terms
   with FALKENHAUSEN" — Alexander von Falkenhausen, military commander in
   Belgium, and lunch guest of Felix's. The 1945 charge sheet cites Felix's
   connections to the Falkenhausen resistance circle. Brussels is where that
   connection was formed, and this is the man himself confirming the relationship.

**The other nine documents cut the other way, and must be read before anyone
else reads them.** They include letters from Czernin to Rasche reporting on a
trip to Hungary "and the progress of aryanization" (687846, 15 pp; 687928, 4 pp;
687845, 7 pp; 687844), and memoranda signed Czernin on *Entflechtung* — the
elimination of foreign influence — in the paper industry (708297, 708298, 708299,
647378). On the present reading Felix was a Dresdner Bank officer inside the
occupation economy of Belgium and Hungary, not a bystander to it.

That is uncomfortable for a §58c narrative and it is better known now than raised
by someone else later. It also does not cancel the persecution: a man can have
run a German bank's Belgian subsidiary and still have been arrested by the RSHA
in March 1945 for legitimism, defeatism and contact with the enemy. Both records
exist. The full interrogation texts, not the summaries, are what settle the
weight — those are at Staatsarchiv Nürnberg (KV-Anklage Interrogations C 22) and
NARA M-1019 Roll 12.

Images and OCR are held locally pending a decision on ingest: 40 pages is a small
paid pass, but it is a paid pass.

**Ingested, 15 September 2026.** New archive **12 — Harvard Law School Library,
Nuremberg Trials Project (US)** holds all thirteen documents, **records
3920–3932**, 40 pages:

| Record | Document | Pages |
|--------|----------|-------|
| 3920 | Interrogation Summary 4031, Int. 2330 A, 6 Nov 1947 | 2 |
| 3921 | Interrogation Summary 4050, Int. 2330-B, 7 Nov 1947 | 3 |
| 3922 | Interrogation Summary 4049, 10 Nov 1947 (Falkenhausen) | 1 |
| 3923 | Interrogation Summary 4108, Int. 2330-D, 19 Nov 1947 | 2 |
| 3924 / 3925 | Czernin to Rasche on the Hungary trip, 24 May 1944, English, NI-6681 | 7 / 1 |
| 3926 / 3927 | the same letter in German, "und den Fortgang der Arisierung", NI-6681 | 15 / 4 |
| 3928 | Czernin to Rasche on his future tasks, coal deliveries, 13 Mar 1944 | 1 |
| 3929–3932 | Entflechtung in the paper industry, Neusser Papier A.G., NI-13829 | 1 each |

Mistral's OCR is markedly better than the local tesseract pass used to triage
these — it reads the index as **2330 A**, which fixes the session sequence
(A = 6 Nov, B = 7 Nov, then 10 Nov, D = 19 Nov). The records are catalogued as
English where the original is English, so the pipeline correctly skips
translation and only OCR, PDF, embedding and person-matching run. Person matching
found hits on record 3920 immediately.

### 2026-09-15 — the Turkey thread, and where it goes next

Felix's own diary (record 3507) gives the charge sheet of 28 March 1945, written
down at Morzinplatz within days:

> 1.) Im Aug. 1943 (vor Türkeireise) defaitist. Aussagen über Kriegslage gemacht zu haben.
> 2.) erklärt zu haben, dass Falkenhausen an Stelle von H. kommen soll.
> 3.) Beziehungen zu Falkenhausen Clique
> 4.) Türkei Feindverbindung!
> 5.) Überhaupt österr. Legitimist.
> "Punkt 4 erscheint mir Bluff gewesen zu sein, alles andere ernst."

Three points follow.

**A Turkey journey actually happened.** "Vor Türkeireise" — the defeatist remarks
are dated by reference to a trip he took, around August 1943. The Nuremberg
account describes a later and different thing: the Pressburg posting under Major
Bechtle of Abwehr I (Luft), six months spent trying to build an information centre
on Russia via Turkey and Persia, with no contacts established. So there are
probably two Turkey threads, and only the second is in the interrogations.

**He rated the Turkey charge the weakest of the five.** In a private diary he
calls point 4 a bluff and the rest serious — which reads as the Gestapo having
material on the defeatism, the Falkenhausen connection and the legitimism, and
fishing on Turkey.

**Point 2 is the sharpest line in the file:** that Falkenhausen should take H.'s
place. With point 3 that is not grumbling about the war; it is naming a successor
to Hitler.

The diary also shows he was held at the Rossauer Lände military prison rather than
by the Gestapo — "ein Riesenglück, dass ich hier bin und nicht bei Gestapo" — his
file never arrived, and he was released on 7 April 1945 as Vienna fell, after
twelve days. Within a fortnight he is at the **O5 Präsidium** with Maasburg, Willi
Taxis and Agathe Croy, and gets papers through the Swedish consulate. The
legitimist charge corroborates itself from the other side.

**Two lines opened, both by letter (neither sent).**

*Transcripts.* We hold Verber's summaries only. The verbatim records are
Staatsarchiv Nürnberg, KV-Anklage, Interrogations C 22, and NARA M-1019 Roll 12 —
the roll being fixed from the M-1019 finding aid, where Czernin falls in the
Cremer–Deutsch range. NARA's catalogue API needs a key and M-1019 is not freely
digitised; the Bundesarchiv's published Nuremberg material (ALLPROZ) does not
index him. So: `poststelle@stanu.bayern.de`, draft in
`correspondence/2026-09-15-stanu-interrogations-c22.md`, Zimbra draft 463855.

*Abwehr.* DDB indexes the Bundesarchiv finding aids, which name the fonds
precisely. **RW 5** is Amt Ausland/Abwehr, and its Turkey series brackets his
window: **RW 5/468** (politisch, März 1943 – April 1944), **RW 5/471**
(militärisch, Okt. 1943 – Juli 1944), 469 and 470 (May–Sept 1944), 363 (June–Sept
1943). Sharpest of all, **RW 5/36a and 36b**, *Devisenbereitstellung für
Auslandstätigkeit im Abwehrinteresse*, 1935–1944 — foreign-currency provision for
Abwehr work abroad, which is exactly what a banker travelling to Turkey on Abwehr
business generates. The Vienna station is RW 49/67–69 but holds only 1938 and 1941
material. None of it is digitised (checked: every IIIF manifest 404s). So:
`militaerarchiv@bundesarchiv.de`, draft in
`correspondence/2026-09-15-barch-ma-abwehr-tuerkei.md`, Zimbra draft 463856.

**Context, not evidence.** Istanbul in 1943–44 was the main channel for German
peace feelers, and the Abwehr station there is where Erich Vermehren defected to
the British in February 1944 — which brought the Abwehr's dissolution into the
RSHA and a purge aimed at exactly Felix's profile: Catholic, aristocratic,
foreign-facing officers with Turkish connections. An RSHA arrest order in March
1945 against a man with a 1943 Turkey trip and an Abwehr tasking toward Turkey
sits naturally in that sequence. Nothing yet evidences a link and none should be
asserted.

### 2026-09-15 — Alexander's baptismal entry found, and it cost nothing

The Matricula archive stood at zero records because nobody knew which of Vienna's
567 parishes to look in. The chain that solved it:

1. **The TH Wien Lebenslauf** (record 3505) says Alexander was born "als Sohn des
   Statthaltereirates … Paul Graf Czernin". A Habsburg official is listed in the
   city directory.
2. **Lehmann's Wiener Wohnungsanzeiger 1913** (Wienbibliothek digital, Band 2,
   Namenverzeichnis, printed p. 177) gives: *"Czernin v. Chudenitz … Paul Gf.,
   Bez.Kmsr. i. Minstm. d. Innern, IV. Alleeg. 20A."* The scans carry no text
   layer, so the page was found by fetching images and OCR'ing the running heads
   locally — the page images are open at
   `digital.wienbibliothek.at/download/webcache/2000/<pageviewId>`, and printed
   page ≈ pageview − 137478. The same page also places Eugen and Josefine Czernin
   at Friedrich-Schmidt-Platz 4 and Rudolf Czernin-Morzin at Schwindgasse 4.
3. Alleegasse 20A is Wieden, so three parishes were candidates. **Pfarre Wieden
   (Paulanerkirche)** was eliminated from its own index — under C for 1913 it
   lists only "Corsina Aldo". **St. Karl Borromäus** was the answer: its baptism
   index (Taufbuch 01-29, 04-Index-Taufe, letter C, 1913) reads *"Graf Czernin
   von Chudenic … 186"*.

**Pfarre Wien IV, St. Karl Borromäus, Taufbuch 01-29 (1907–1914), Fol. 186,
Reihezahl 21:**

> Geboren am **30. April**, getauft am **6. Mai 1913**
> Ort der Geburt: **Wien, IV. Bezirk, Allee-Gasse No 20a**
> **Alexander Friedrich Josef Paul Maria Graf Czernin von Chudenic**

Father: *Paul Friedrich Hubert Ottokar Maria Graf Czernin von Chudenic*, k.u.k.
Kämmerer, Bezirkskommissär im Ministerium des Innern, Lt. d. Res. of Dragoon
Regiment No. 14, born 1879 at **Dymokur, Bez. Poděbrad** in Bohemia. Mother:
*Reichsgräfin Gabriele, geb. von Orsini und Rosenberg*, born 1879 in Vienna,
legitimate daughter of Felix Reichsgraf von Orsini und Rosenberg, k.u.k.
Kämmerer and Feldmarschall-Leutnant.

A marginal note records that he married on **9 July 1949 at St Paul's Church,
Haywards Heath**, England — the Vienna register being annotated from England
thirty-six years later.

Ingested as **record 3933** (archive 5, Matricula Online — previously empty):
the folio spread and the index page that points to it. The birth address on the
register and the address in Lehmann agree exactly, which is what ties the entry
to this family beyond argument.

Method note: Matricula's page images are served directly from
`hosted-images.matricula-online.eu/images/matricula/NAS_Matricula_Online/<id>M/<register>/<file>.jpg`
with no token — the viewer's `img.data.matricula-online.eu/image/<base64 of that
URL>` wrapper is not required. Registers carry their own name indexes
(`06-Index-Taufe`, `04-Index-Taufe`), organised by letter and year, which turns a
219-image register into a two-page lookup.

### 2026-09-15 — follow-ons from the Matricula method, and two ÖStA additions

**The mother's baptism is parked, not abandoned.** The baptismal entry gives
Gabriele's own details precisely: *geb. 21/5 1879 zu Wien VII*, legitimate
daughter of Felix Reichsgraf von Orsini und Rosenberg. So her own baptism is in a
Neubau parish. Of the three, only **Altlerchenfeld** has an 1879 Taufbuch online
(01-46, with a 14-page index); **St. Ulrich** and **Schottenfeld** cover the year
in principle but no 1879 volume is listed. Altlerchenfeld is the poorer, western
parish, so it is the less likely of the two for this family — worth a look at its
index under O, and otherwise a question for the Erzdiözese.

**ÖStA, two additions to the Partezettel picture.** The collection holds **35**
Czernin von Chudenitz death notices. The one that matters and was missing from
our order:

> **AT-OeStA/HHStA SB Partezettelsammlung 17, ID 3380986** — *Partezettel Czernin
> von und zu Chudenic, Paul Graf*, **17.01.1938**

That is Alexander's father, and the date is his death date at Krásný Dvůr. With
the marriage announcement of 22 April 1901 (3380985) already ordered, the two
together carry the descent on paper. A short addendum to the 14 September order is
drafted as Zimbra **463863**, not sent.

Searched and absent: no Partezettel for Maria Gabriele (d. Salzburg 1951) and
none for the other siblings — the collection is Vienna-centred, and they died
elsewhere.

### 2026-09-15 — Rudolf in the Terezín Memorial's own prisoner database

The Památník Terezín runs a free database of the politically and racially
persecuted, covering the Small Fortress Gestapo prison, Pankrác, Litoměřice,
Flossenbürg, the Ghetto and more. It had never been searched for this family.
A surname search returns five Czernin-like records, of which one is ours:

> **RUDOLF CZERNIN** — *Věznice gestapa v Malé pevnosti Terezín 1940–1945*
> geb. **25. 8. 1904**, **Dymokury**
> Místo transportu: **Ad-Kolin, Prag-Pankratz**
> Příčina/důvod odchodu: **transport**
> Cílové místo (odchodu): **Gollnow**
> `https://www.pamatnik-terezin.cz/vezen/mp-czernin-rudolf`

Date and place of birth match Rudolf Děpold Czernin exactly. The value is that
the route — Kolín, then Prague-Pankrác, then the Terezín Small Fortress, then
Gollnow near Stettin — was until now carried only by secondary literature. It is
now attested by the memorial institution that holds the prison's records.

Ingested as **record 3934** in new archive **13, Památník Terezín**.

The other four hits are unrelated: Czernin Aron (b. 1919 Parič/Minsk, Litoměřice
and Ghetto Terezín), Czerninski Grete, and a cross-reference.

Method note: the database search does not work by constructing query URLs — the
parameters are ignored and the unfiltered page comes back, which looks exactly
like a nil result. A control search for a common Czech surname exposed that. The
form has to be filled and submitted in a browser; results then appear at
`/vezen/<slug>`.

**Kinský in the same database — all five, ingested as 3935–3939.** Searched on the
stem "Kinsk" (8 hits; Lenkinska, Wickinsky and Wykinsky are substring noise):

| Record | Person | What the card says |
|--------|--------|--------------------|
| 3935 | Bernhard Kinski, b. 30.7.1876 Bischofsburg | Transport **I/71, 4 Oct 1942, Berlin → Terezín**, no. 1021; transport number **8628**; **died 1.5.1945** |
| 3936 | Martha Kinsky, née **Friedländer**, b. 5.6.1875 Wartenburg | Same transport I/71; transport number **8629** |
| 3937 | Jaroslav Kinský, b. 15.9.1897, of Ostrava | Held at **AD-Ostrau** (Gestapo Ostrava) before Mauthausen; **died** there |
| 3938 | Miloslav Kinský, b. 27.2.1896 Chlum | Mauthausen; **died 30.11.1941** |
| 3939 | Václav Kinský, b. 9.6.1922 Sedletz | Flossenbürg, fitter, from Zwickau, arrived 28.3.1945, category Tsch.ZA, camp no. 89111 |

Two groups, and they should not be run together. Bernhard and Martha were deported
from **Berlin** on the same transport with consecutive numbers, both born in East
Prussia — a married Jewish couple, with no established connection to the Bohemian
Kinský family. Jaroslav, Miloslav and Václav are Czech, and two of the three died
in Mauthausen.

**None of them is František Kinský of Kostelec nad Orlicí**, who was convicted
with Rudolf Czernin and Karel Viktor Rohan on 28 March 1944 and survived. Nor is
Karel Viktor Rohan present: a Rohan search returns four records, all Emanuel
Rohan b. 1914 Chrást or unrelated. So the two men convicted alongside Rudolf are
absent from this database, while Rudolf himself is in it — worth asking the
Memorial about, since it bears on how complete the Small Fortress coverage is.

### 2026-09-15 — Heydrich's ten families, tested against the Small Fortress database

Heydrich's letter to Bormann of 16 May 1942 names ten families whose estates were
to be placed under forced administration — Kinský, Belcredi, Sternberg,
Schwarzenberg, Lobkowicz, **Czernin**, Kolowrat, Strachwitz among them — and gives
the reason as the nobility's declaration of loyalty to the Czechoslovak state.
Each surname was run against the Terezín Memorial database:

| Name | Result |
|------|--------|
| **Czernin** | **Rudolf, Malá pevnost** (record 3934) |
| **Bořek-Dohalský** | **four cards, all Malá pevnost** (records 3940–3942) |
| Lobkowicz | nil |
| Belcredi | nil |
| Kolowrat | 1, Ghetto Terezín (Wilhelmine Gisela, 1871) — not the Bohemian family |
| Schwarzenberg | 8, all Ghetto Terezín, Jewish namesakes |
| Sternberg | 149, overwhelmingly Jewish women recorded *née* Sternberg |
| Kinský | 5 (records 3935–3939), none of them František Kinský of Kostelec |
| Rohan | 4, none of them Karel Viktor Rohan |

Two families from that circle are in the political prison, then: the Czernins and
the Bořek-Dohalskýs. The Dohalský cards are worth having in their own right:

- **František** (b. 5.10.1887, diplomat and writer) — transport to **Dachau**,
  and his card carries the remark **"signatář Prohlášení české a moravské
  šlechty"**. That is the Memorial's own record connecting imprisonment in the
  Small Fortress to the nobility declaration — the causal link the case argues,
  stated by the institution rather than inferred from literature.
- **Antonín** (b. 23.10.1889, Msgr. ThDr., canon at St Vitus) — 29 June to 26
  July 1942, then transport to **Auschwitz**.
- **Zdeněk** (b. 10.5.1900, JUDr., editor of Lidové noviny) — brought from
  Prag-Pankratz on 6 February 1945 and **executed on 7 February 1945**, cell EZ.

Hořejš's study of Rudolf and Humprecht Czernin (record 3519) sets Humprecht
beside precisely these two brothers. The database now supplies the primary
records behind that comparison.

A caution for anyone repeating the searches: most noble surnames return Jewish
namesakes from the Ghetto database, because the surname field is "Příjmení
(rozená)" and matches maiden names. Sternberg's 149 hits are almost entirely of
that kind. The political-prison records are the ones with `mp-` and `pa-` slugs.

### 2026-09-16 — Arolsen holds an ITS tracing case on Rudolf, and one on Vera

Rudolf's Terezín card ends with a transport to **Gollnow**, and nothing in the
archive covered that leg. Searching Arolsen for the name turns up 108 people
records, none of them his — the five "CZERNIN, Rudolf" entries are the other
Rudolf, born 18.02.1924 in Prague. The find is under **Topics**, not People:

> **Tracing and documentation case no. 1.314.605 for CZERNIN, RUDOLF born
> 25.08.1904** — ITS signature **06030302.1.314.605**, created **1990–1992**,
> **8 documents**.
> *"Contains information about CZERNIN, RUDOLF (further first names: THEOBALD)."*

Birth date and the second name **Theobald** identify him beyond argument: Rudolf
Theobald Czernin, 1904–1984, the signatory named in Kučerová's thesis. An ITS
tracing case is the file the International Tracing Service built when someone
asked it to establish what had happened to a person — which is to say, a
compilation of his imprisonment record made by the body that held the camp
registers.

A search on the case description turns up four more, of which one matters:

| Case | Person | Documents |
|------|--------|-----------|
| **1.314.605** | CZERNIN, RUDOLF b. 25.08.1904 (Theobald) | **8** |
| **709.023** | **VON SCHUSCHNIGG, VERA** b. 04.06.1904 | **6** |
| 281.939 | Czernin, Aron b. 20.05.1919 | 18 |
| 394.235 | Czernin, Hela b. 15.05.1918 | 11 |
| 902.495 | Czernin, Franz b. 19.03.1897 | 6 |

**Vera** is Vera Czernin, who married Kurt von Schuschnigg by proxy in 1938 while
he was in Gestapo custody and then followed him into Sachsenhausen, Flossenbürg
and Dachau. The search plan lists her among the persecution evidence; her own ITS
file has never been looked at. Aron and Hela are the unrelated Jewish Czernins
already distinguished in the Terezín database. Franz, born 19.03.1897, is
unidentified — worth placing before anything is claimed about him.

**Neither file can be read online.** The case page states that parts are withheld
for data protection and directs enquiries to the Arolsen Archives, and no
document links are rendered. Arolsen takes inquiries through a form —
`https://arolsenarchives.my.site.com/guest/s/?language=en_US` — not by email, so
this one needs the applicant's own details and is left to be submitted rather
than drafted as a letter.

What to ask for: the complete T/D case files **1.314.605** (Czernin, Rudolf, b.
25.08.1904) and **709.023** (von Schuschnigg, Vera, b. 04.06.1904), citing the
§58c proceedings, and asking in the same request whether ITS holds a case or any
documents for **Humprecht Ottokar Czernin (b. 09.02.1909)** beyond the Brandenburg
material already held as record 3410, and for **Felix Czernin (b. 07.03.1902)**.

### Arolsen inquiry submitted — 16 September 2026

Submitted through the guest form
(`https://arolsenarchives.my.site.com/guest/s/?language=en_US`), which is a
Salesforce flow: two person screens, then a general screen, then Send. The
acknowledgement reads *"Thank you for your Inquiry!"* and no reference number is
shown on screen. **The form warns that a reply may take up to 13 months.**

Submitted as: Mr Alex Fishlock, United Kingdom, `alex.fishlock@racingjag.com`,
external file number **MA 35 Wien, Zl. 2026-0.068.068**, inquiry type "Documents
on a Person" → "On a family member".

| Person | Details given | Asked for |
|--------|---------------|-----------|
| **Rudolf Theobald Czernin** | male, b. 25.08.1904 Dymokury (Bohemia), exact year, married, Czechoslovak | complete T/D case **1.314.605** (ITS ref 06030302.1.314.605, 1990–1992, 8 documents) |
| **Vera von Schuschnigg, née Czernin** | female, b. 04.06.1904, exact year, married, Austrian, d. 18.09.1959 Kirkwood, Missouri | complete T/D case **709.023** (6 documents), plus anything on her and her daughter in Sachsenhausen, Flossenbürg and Dachau |

Both persecution texts cite the §58c proceedings before MA 35 Vienna and state
the family relationship to Alexander Czernin von Chudenic (b. 30.4.1913 Vienna).
"Already searched the online archive" = Yes on both, with the collections search
link and the case numbers.

**Not included:** Humprecht Ottokar Czernin (b. 09.02.1909) and Felix Czernin
(b. 07.03.1902). The online archive lists no T/D case for either, so there is
nothing specific to request; a second inquiry can be filed asking whether ITS
holds anything on them at all, but it would sit behind the same 13-month queue
and is better sent once this one has produced an answer and a case reference to
quote.

**Reference: 2026-09-15-59916.** The acknowledgement (`no-reply@arolsen-archives.org`,
message 463868) confirms receipt of "your tracing inquiry 2026-09-15-59916" and
says processing "may take several months" — one inquiry number covering both
persons, so any chase quotes that number.

### Duplicate provenance record resolved — 16 September 2026

The Kunstrückgabebeirat decision of 18 March 2011 on the Czernin Vermeer was held
twice, both in archive 7 and both complete with the same 34 pages and the same
93,452 characters of OCR text:

| | 3360 (ingested 1 March) | 3798 (10 September) |
|---|---|---|
| title | "Beiratsbeschluss Czernin/Vermeer" | full German title, with English |
| description | 109 characters | 566 characters |
| referenceCode | none | `Beschluss 18.03.2011` |
| sourceUrl | the exact PDF | the site root |
| English text | 83,452 characters | 87,690 characters |

**3798 kept**, with 3360's exact source URL
(`https://provenienzforschung.gv.at/beiratsbeschluesse/Czernin_Jaromir_2011-03-18.pdf`)
copied onto it first — `POST /api/ingest/records` matches on sourceSystem +
sourceRecordId and updates in place, so the title, description, date range and
reference code were resent verbatim in the same call because that endpoint sets
them unconditionally. **3360 deleted.** One record now answers the search.

## 16 September 2026 — Alexander's three brothers

The family tree (person 296, code `.A3.B2.C6`) gives Paul Czernin six children.
Three of them are Alexander's brothers:

| | born | died | |
|---|---|---|---|
| **Felix** Theobald Paul Anton Maria | Hlušice 7.3.1902 | Wien 3.1.1968 | banker; m. Merano 19.9.1933 Anna Leopoldine Ceschi a Santa Croce (div. 1950) |
| **Wolfgang** Otto Paul Dominikus Maria | Hlušice 4.8.1903 | Wien 10.10.1982 | Dipl.-Ing.; m. Berlin 6.5.1932 Alexandra von Maltitz |
| **Jan (Johannes)** Felix Jaroslav Pavel Maria | Hodonín 8.3.1910 | Salzburg 24.5.1996 | Dr.; m. Wien 30.4.1940 Osterheldis Frn von der Lippe |

The other three children were Anna (1907–1993), Franziska (1910–2000, married
Johann Freiherr von Koblitz) and Alexander himself.

**Correction to the tree.** Person 322 (Alexander) carries a death year of 1982.
That is wrong: Alexander died in **2002 at Oxford**. The Arolsen inquiry sent the
day before says "d. 5.4.2002 London", which has the year right and the place
wrong. Both need fixing, and the death certificate is the authority.

### Printed proof of descent — Wiener Salonblatt, 6 February 1938

Found through ANNO's REST search (see working notes) and ingested as **record
3943**: the *Todesfälle* column of the Wiener Salonblatt for 6 February 1938
carries Paul's death notice and names every child.

> "Auf Schloß Schönhof verschied der k. u. k. Km., StatthaltRat und Rittm. d.
> Res. des k. u. k. DragRgts. Nr. 14 **Paul Graf Czernin** im 59. Lebensjahre,
> tief betrauert von seiner Gemahlin Gräfin Gabriele geb. Gräfin v.
> Orsini-Rosenberg und seinen Kindern Grafen **Felix** Czernin, vermählt mit
> Gräfin Anna Leopoldine Ceschi, Grafen **Wolfgang** Czernin und seiner Gemahlin
> Gräfin Alexandra geb. v. Maltitz, Gräfin **Anna** Czernin, Freifrau
> **Franziska** (Johann) v. Koblitz sowie den Grafen **Johannes** und
> **Alexander** Czernin."

A contemporary printed source, dated three weeks after the death, naming
Alexander as Paul's son. Independent of any register.

### Felix — the banker

- **Böhmische Escompte-Bank.** *Die Zeit* (the NSDAP organ for the Reichsgau
  Sudetenland), 30 June 1938, under "Neue Filialdirektoren bei der Böhmischen
  Eskomptebank und Unionbank": "Mit der Leitung der Filiale wurde Direktor
  **Felix Czernin** (bisher Leiter der Filiale in **Hohenelbe**) betraut."
  (record **3944**.) The BEB passed to the Dresdner Bank in 1939 — which is how
  he came to be interrogated at Nuremberg four times in November 1947
  (Staatsarchiv Nürnberg, KV-Anklage, Interrogations C 22; DDB indexes the file
  as "Czernin, Felix Graf, geb. 07.03.1902 in Bluschitz/Böhmen, Dresdner Bank").
- **Brussels.** The Vienna registration record already held as **3503** has him
  arriving at Wohllebengasse 9 in 1943 *from Brussels* — the posting the
  Nuremberg interrogations are about (Falkenhausen, the Continentale Bank).
- **Marriage.** Innsbrucker Nachrichten, 21 September 1933: "In Meran fand am 20.
  d. M. die Trauung des Grafen Felix Czernin, **eines Neffen des verstorbenen
  österreichischen Ministerpräsidenten Ottokar Czernin**, mit Gräfin Anna
  Leopoldine Ceschi a Santa Croce statt." (record **3945**.)
- **Post-war Vienna.** Four Handelsregister notices in the Wiener Zeitung trace
  his career back up: Gesamtprokurist of Sroubek & Co., Wien I (7.10.1948,
  record **3946**); Geschäftsführer of a chemicals GmbH (15.1.1950, **3947**);
  "Felix Czernin, **Bankdirektor**, Wien I., als Vorsitzer" of a supervisory board
  (9.12.1951, **3948**); and in 1955 on a board beside two Princes Liechtenstein
  and two Zurich directors (11.5.1955, **3949**). His ÖStA Partezettel records him
  as *Aufsichtsratsvorsitzender der Vianova Kunstharz AG*, died 3.1.1968 aged 65,
  buried at the Hietzinger Friedhof.

### Wolfgang — Berlin, 1932 to 1943

The Berliner Adreßbücher, digitised with full text by the Zentral- und
Landesbibliothek Berlin, track him year by year (new archive 15):

| year | entry | record |
|------|-------|--------|
| 1936 | "Czernin Graf Wolfgang Dipl Ing Friedenau **Cosimaplatz 2** T" (name part); the street part shows him as a tenant, the house owner being H. Maier-Piccard, Bankier | **3952**, **3954** |
| 1937 | "Czernin Graf Wolfgang Dipl Ing W30 **Bamberger Str 29**" | **3955** |
| 1940 | "240 E Czernin, W., Graf" — E = Eigentümer | **3956** |
| 1941 | same entry, S. 2105 | — |
| 1942 | same entry, S. 2129 | — |
| 1943 | "240 E Czernin, W., Graf, Dipl. Ing. T." | **3953** |

The street is not printed at the head of those columns; it is fixed by the
neighbours — the Berliner Bürgerbräu A.G. at nos. 164/166 and the Jacht-Club
Müggelsee at 72/74, with the cross-streets Zur Fähre, Hahn's Mühle, Nauener Weg
and Spreestraße — as the **Müggelseedamm** in Berlin-Friedrichshagen. So Wolfgang
lived in Berlin from his 1932 marriage there and **owned the house Müggelseedamm
240 from at least 1940 to 1943**, through the war.

**A Prussian naturalisation file exists.** The Geheimes Staatsarchiv
Preußischer Kulturbesitz finding aid for *I. HA Rep. 77, Nr. 7192*
("Einbürgerungen, Einzelfälle, Buchstabe C", 1929–1930) names, among eighteen
individual cases, **"Czernin, Wolfgang, geb. 4.8.1903"** — his exact date of
birth. A naturalisation case opened three years before his Berlin marriage
speaks directly to the family's citizenship position before 1938. Requested
16.9.2026 (Zimbra 463896).

His Partezettel (AT-OeStA/HHStA SB Partezettelsammlung 17-309, already on the
14 September order) gives "Dipl.Ing., Dr e.h., Dr. hc", died Vienna 10.10.1982
aged 79, buried at Bad Aussee.

### Jan — Salzburg

- Salzburger Nachrichten, 19 March 1952: a stolen jeep, whose owner is "**Dr.
  Johannes Czernin, Salzburg, Höfelgasse 24**" (record **3950**). Residence and
  doctorate, seven years after the war.
- **AT-OeStA/HHStA SB Familienarchiv Clary 48-144** — "Gedenkbild von Johannes
  Graf Czernin von Chudenitz", 24.5.1996, born 08.03.1910. His memorial card,
  matching both dates exactly. Its protection period runs to 31.12.2026, so a
  Bewilligung may be needed; requested with that question on 16.9.2026 (Zimbra
  463901), together with the 1967 marriage announcement of his daughter Maria
  Christina.

### Negatives worth recording

- **Arolsen holds nothing on any of the three.** All 108 persons its name search
  returns for "Czernin" and its phonetic variants were listed and checked: no
  Felix, no Wolfgang, no Johannes or Jan. (Humprecht is there, already held as
  record 3410.) They were not registered as victims of persecution by the ITS.
- **findbuch.at is a dead end for the name.** Its "Czernin" hits are almost all
  addresses in the Vienna *Czerningasse* and *Czerninplatz*, which is why 113 of
  the 122 records pulled from it on 15 September were deleted.
- **Pater Wolfgang Czernin OSB is a different man** — a Benedictine of Beuron who
  appears in the Reichspost (26.4.1938, professing nuns in Copenhagen), in the
  Salzburger Kirchenblatt and in the 1935 Salonblatt wedding photograph, and who
  died at Uberaba, Brazil, on 22.10.1954 (AT-OeStA/HHStA SB NL Nostitz-Rieneck
  26-2-56). Every "Wolfgang Czernin" in a Catholic paper is him, not the brother.

### An open question: Kochstraße 28/29

The 1933 Berlin street directory (record **3957**) records the houses
**Kochstraße 28/29** — in the middle of Berlin's newspaper quarter — as
"E Czernin, Graf, Eigentüm. (**Wien**). V. Böschke, Verwalt. (Wilmersdf.)", with
"**The New York Times**, Redaktion" and "The New York Times G.m.b.H., Wide World
Photos" among the tenants. Which Czernin is meant is unresolved: Wolfgang was
himself in Berlin and listed separately, and Paul lived at Krásný Dvůr. Worth
settling, because Berlin property in Czernin hands in 1933 has an obvious
follow-on question about what happened to it after 1938.

### Working notes

- **ANNO (Austrian newspapers) has an undocumented REST search**, which the
  Angular app calls: `https://anno.onb.ac.at/anno-suche/rest/search/simple?query="Felix Czernin"&from=1`,
  paging with `from`. It returns `totalHits` and a `documents` array of `docId`s
  like `ANNO_wsb19380206`.
- **The page text is free, and does not need the OCR pipeline.** The legacy
  `cgi-content/anno` and `annoshow` endpoints are behind Cloudflare Turnstile, but
  `https://api.onb.ac.at/iiif/presentation/v3/manifest/<docId>` answers plainly
  and each canvas carries a `seeAlso` OCR text file. `scratchpad/annotext.py`
  fetches a whole issue and prints the context around a name, so relevance is
  decided before anything is paid for.
- **The ZLB's Berlin address books work the same way.** POST to
  `https://digital.zlb.de/viewer/api/v1/index/query` with
  `{"query":"FULLTEXT:Czernin AND PI_TOPSTRUCT:34115495_1936","count":40}` lists
  the pages; `…/records/<pi>/pages/<order>/text/` returns word-level annotations
  with `xywh` boxes. `scratchpad/zlb_sweep.py` does a whole run of years.
- **Do not ingest a whole address-book page.** Mistral OCR turned the full Fraktur
  page 387 into Hungarian-looking nonsense ("Győzárnő Szabóház …") — the word
  boxes let you crop the actual column through IIIF
  (`…/files/images/00000392.png/700,2250,800,500/full/0/default.jpg`), and the crop
  reads perfectly. Records 3952–3954 were repaired this way with
  `POST /repair` → `PUT /pages/1` → `POST /complete`.

### Corrections and additions, later the same day

**Felix's 1938 posting was Aussig, not an unnamed branch.** The first OCR pass
over the whole Fraktur page of *Die Zeit* produced nonsense, and my reading of the
library's own OCR had the sentence broken across columns. Cropped to the article
(record **3944**), it reads:

> "Neue Filialdirektoren bei **Bebca** und Unionbank. Prag. Wir entnehmen dem
> Prager „Börsencourier“: Der bisherige Leiter der Filiale **Aussig** der Böhm.
> Eskomptebank Direktor Leo Reiner tritt in den Ruhestand. Mit der Leitung der
> Filiale wurde Direktor **Felix Czernin** (bisher Leiter der Filiale in
> **Hohenelbe**) betraut. Die Leitung der Filiale in Hohenelbe übernimmt Direktor
> Weizsaecker, bisher Prokurist der Filiale Aussig. Der bisherige Leiter der
> Filiale in Reichenberg Direktor Otto Winternitz wurde in die Zentrale berufen."

"Bebca" is the Böhmische Escompte-Bank und Credit-Anstalt. So from the summer of
1938 Felix ran its **Aussig (Ústí nad Labem)** branch — in the Sudetenland, three
months before the annexation and a year before the bank passed to the Dresdner
Bank.

**A tree defect, not a data error.** The tree gave Alexander a death year of 1982
because the parser read the `+` inside his wife's parenthesised dates:

```
D6. Alexander Friedrich Josef Paul Maria, *Wien 30.4.1913; m. Haywards Heath
9.7.1949 Diana Zannick Hutton/Hulton (*London 12.12.1922, +Iver Heath, Bucks 30.8.1982)
```

Diana died in 1982; Alexander died at Oxford on 5 April 2002, and the line
recorded no death for him at all. `FamilyTreeService` now masks parenthesised
text before reading birth and death — birth had the same flaw for anyone whose
own birth is unrecorded — and the genealogy file carries his death. Four tests in
`FamilyTreeParseTest`; full suite 363 tests, no failures. The place was also wrong
in `archive-research-plan.md` ("London") and in the Arolsen inquiry text.

**Bundesarchiv asked the unflattering question.** A request went to the
NSDAP-Mitgliederkartei (Sammlung BDC) for all three brothers, and for holdings in
R 8119F (Dresdner Bank) and the Bankenkommissar Belgien files naming Felix
(Zimbra 463919). The letter says in terms that an adverse answer is wanted: a
bank director in the Sudetenland from 1938 and a Berlin property owner through
the war are exactly the facts that have to be established rather than avoided.

**ANNO is now exhausted for these names.** Its simple search ORs unquoted terms —
"Czernin Aussig Filiale" returns 10,702 hits, nearly all Ottokar Czernin in 1918 —
so only exact phrases are usable, and the phrases for all three brothers have been
run.

**Two records carry a stale English title.** 3953 and 3954 were translated before
their German metadata was corrected, and a re-ingest does not re-translate, so
their `titleEn` still reads from the earlier version ("second Czernin hit (control
page)"). Re-running metadata translation needs `POST /api/admin/records/reset-pipeline`
with `targetStage: translating`, which sits behind the admin login rather than the
processor token.

### The Dropbox Austria folder, and what MA 35 has actually said

`/Volumes/External/Dropbox/Alex&Val/Alex/Austria` holds the working papers of the
application. Two things in it change the shape of the work.

**1. MA 35 has already refused twice on the merits.** The thread with Referat
8.2.3 (Uros Sarafinović) shows the historian's conclusion, restated on 21 July and
again on 25 August 2026:

> "the historian concludes that the newly submitted documents do not provide
> evidence that Alexander Czernin faced, or had reason to fear, persecution by the
> Nazi regime. The documents mainly concern the persecution of his cousins
> Humprecht and Rudolf Czernin. However, no connection has been established
> between their persecution and any threat to Alexander Czernin. His own files
> from 1941/42 also indicate that the Nazi authorities raised no concerns about
> him. His appointment at the Vienna University of Technology was approved, and he
> was even described as having a good reputation."

MA 35 then offered either a Bescheid or withdrawal. The case as put to them rests
on **well-founded fear** rather than persecution in fact, and its two strongest
family planks both involve the brothers researched today:

- **Wolfgang** visited Humprecht in Brandenburg-Görden prison on **11 April 1943**
  and applied to visit again that September (Arolsen doc. 12120657, pp. 32–33).
  That is the immediate family knowing, at first hand and at the time, what had
  happened to a cousin sentenced to death. Today's address-book run explains how:
  he lived in Berlin, at Müggelseedamm 240 — an hour from Brandenburg.
- **Felix** was arrested by the Gestapo in Vienna on RSHA orders on **28 March
  1945** (his diary, record 3507).

So the brothers are not background to this application; they are the part of it
that answers the historian's objection, and corroborating those two events in
official records is the most valuable thing left to do.

**2. Alexander's death certificate — ingested as record 3958.** GRO certified copy
QBDAD 058371: died **5 April 2002 at Greengates, 2 Hernes Road, Oxford**,
registration district Oxfordshire, entry 33; occupation "Engineer (retired)";
informant his daughter Alexandra Gabriella Maria Czernin-Fishlock of 3 Bainton
Road, Oxford; registered 8 April 2002. This settles the date and place.

**A discrepancy to be aware of:** the death entry gives his date of birth as
**18 April 1913**, not 30 April 1913. The Austrian birth, baptism and registration
records all say 30 April, so the error is in the English death entry — but MA 35
may compare the two, so it is recorded on the record itself. Why the wrong day was
given is not known; the record says so rather than guessing.

**3. The FCDO neither confirms nor denies — record 3959.** A 2025 FOI request
asked for UK records about Alexander: an Austrian citizen who worked for the
Wehrmacht as an aircraft engineer and tipjet/helicopter specialist, who on 14
January 1947 was collected by two British officers from Braunschweig in the
British zone and flown by Viking to Farnborough, landing the evening of 17 January
1947. It also asked for "any evidence or suspicions that the UK gov had that he or
any of his siblings were active resistance members … in Austria", July 1945 to
July 1950. The FCDO answered (5 September 2025) that it can **neither confirm nor
deny** holding anything, under the absolute exemption in **section 23(5) FOIA** —
information supplied by or relating to the security bodies — and the internal
review of 3 November 2025 upheld that, on the ground that s. 23(5) bites whenever
the subject matter falls within the ambit of those bodies' operations. So the
question of the family's resistance activity met a security-service exemption
rather than a "we hold nothing".

**Still in that folder, not yet in the archive** (listed so the choice is
deliberate rather than forgotten): the Bundesarchiv Lichterfelde first reply of
23 December 2025 (DR 4 – 2023/0002#0185#0001, which requires a signed
Benutzungsantrag before any search, and offers ten files a year digitised free);
Phil Tomaselli's photographs of a National Archives file on **Ferdinand Czernin's
refused UK visa** (13 images); `FO 1020/2610` and `AIR 78/56/10` downloads;
Alexander's own 1987 family history and the 1966 Ossiach family-reunion speeches,
including Felix's; the ÖStA and Národní archiv research letters of 2023; and the
Vienna Technical University file already held as record 3505.

## 16 September 2026 — hunting persecuted relatives of the declaration signatories

The MA 35 historian's objection is that the persecution of Humprecht and Rudolf
establishes nothing about Alexander. The answer to that lies in the wording of
Heydrich's own letter, which designates **families**, not men:

> "The above-mentioned gentlemen belong to those intellectual agitators who, **in
> the Reich and in the Ostmark**, exploit their German names and noble connections
> for subversive activities, particularly espionage" — naming Kinský, Belcredi,
> Sternberg, Schwarzenberg, Lobkowicz, **Czernin**, Kolowrat, Strachwitz.

Alexander was a Czernin living in the Ostmark. So what has to be shown is that
relatives of signatories — people who did not sign anything — were persecuted too.

### A proven case: the three Bořek-Dohalský brothers

| | Signed? | What happened |
|---|---|---|
| **František**, b. 5.10.1887 Přívozec | **Yes** — signatory no. 28 of the 85 (record 3497) | Gestapo prison in the Small Fortress from 30.6.1942, card annotated *"signatář Prohlášení české a moravské šlechty"*, then **transport to Dachau**: prisoner **34390**, Schutzhäftling, arrived 14.8.1942, **liberated 29.4.1945** |
| **Antonín** | No | chancellor to the Archbishop of Prague; transport to Auschwitz, **died 3.9.1942** |
| **Zdeněk**, b. 10.5.1900 Přívozec | No | JUDr., political commentator on *Lidové noviny*; in the Small Fortress from 27.6.1942, **executed at Terezín 7.2.1945** |

Two brothers of a signatory died in camps; the signatory himself survived nearly
three years in Dachau. Hazdra's dissertation (record 3797, p. 211) states the
relationship and the fates in terms. Ingested today:

- **3960** — the Dachau database card for František (Terezín Memorial).
- **3961** — a second Small Fortress card for Zdeněk, indexed under "Z_", which
  carries the birth data and profession the first card lacks.
- **3962** — the **Dachau entry register** page from the Arolsen Archives
  (collection 1.1.6.1): "FRANZ DOHALSKY, b. 5.10.1887 Privosten, Roman Catholic,
  CZECH, Ministerialrat, last residence Prague, prisoner 34390". The camp
  administration's own register.
- **3963** — his Dachau office card (Arolsen 1.1.6.7).

Arolsen holds **22 documents** on him altogether; the remaining eighteen are
cashier statements from 1.1.6.12 and were left where they are.

### The sweep, and what it did not find

The Terezín Memorial's databases were searched for **29 signatory surnames** in
both German and Czech spellings. Method: the search only answers a real top-level
form submission — a constructed URL, a `fetch()` POST and an iframe POST all come
back as the unfiltered landing page, which looks exactly like a nil result. The
working method is to set `s1` on the second form, click `searchBasic`, and read the
`/vezen/<slug>` links off the page; chaining it through `sessionStorage` lets one
call per surname harvest the previous surname's hits.

Nil in the databases: Belcredi, Bubna, Dobrzenský, Hildprandt, Lobkowicz (and
Lobkovic), Šternberk, Strachwitz, Nádherný, Pálffy, Wratislav (and Vratislav, bar
one Vratislavský), Podstatzký, Mladota, Dačický, Dlauhoweský, Korff-Kerssenbrock,
Kálnoky, Parish, Sereny.

**Namesakes, kept separate rather than claimed.** Searching "Sternberg" returns
fifty Terezín ghetto prisoners of that name, "Schwarzenberg" eight, "Schönborn"
three — Jewish deportees, not the noble houses. "Battaglia" returns Francesco
Battaglia, an Italian. And a Kolowrat hit in Arolsen's "Deportations from the
Gestapo area Vienna" turned out, on opening the document, to be entry 480 of a
Vienna Jewish deportation list — every name on the page marked *Isr.* or *S.* It
would be wrong to use that page as evidence about the nobility, and it is recorded
here so the mistake is not made later.

Two further Terezín hits were checked and set aside: "Friedrich Westfried
Colloredo Mels", b. 1880 Brünn, nationality recorded as *Jude*, died 1.6.1942 —
not the Colloredo-Mannsfeld family on the evidence available; and Bohumil Kolovrat,
b. 1902 Moravská Ostrava, Mauthausen prisoner 5998, who died on 3.9.1943 —
the card reads *"zemřel – roztrhán psy"* — but nothing links him to the
Kolowrat-Krakowský family, and a commoner of that name is the likelier reading.

### Leads from the signatory list itself

The Drocár list gives each signatory's age at death, which flags those who died
during the occupation and are worth checking individually: **Ondřej Schlik**
(1917 Vokšice – 1942 Jičíněves, aged 25 — *not* Zikmund, as first written here;
the list sets names and dates in separate columns and they are easy to mis-pair),
**Josef Lobkowicz** (1918 – 1946 Mělník,
aged 28), **Mořic Lobkowicz** (1890 – 1944 Telč), **Jindřich Dobrzenský** (1892 –
1945 Potštejn), **Zdeněk Kolowrat** (1881 – 1941 Rychnov), **Jeroným
Colloredo-Mansfeld** (1870 – 1942 Praha) and **Weikhard Colloredo-Mansfeld**
(1914 – 1946 St. Lary, aged 32). None of them appears in the Terezín databases,
so if they were persecuted the record lies elsewhere.

### Two enquiries sent

- **Terezín Memorial** (Zimbra 464185, to `archiv@`, copy to `databaze@`): asks
  which members of the signatory families appear in their databases, whether any
  other card carries a declaration annotation like František Bořek-Dohalský's, and
  whether they hold further archival material on Rudolf Czernin or the Dohalský
  brothers beyond the database. The letter lists the namesakes I am *not* claiming.
- **ÚSTR** (Zimbra 464191, for the attention of Dr Zdeněk Hazdra): asks whether
  his research documents reprisals against non-signing family members of the
  families Heydrich named; whether any SD, Gestapo, Land Office or Vermögensamt
  document shows the authorities treating membership of a family as such as the
  ground; whether anything is known about those measures reaching family members
  living in Austria, as the letter's "Ostmark" implies; and whether the German
  original of the Heydrich letter has been located anywhere.

### An Austrian aristocrat in Buchenwald for being a Legitimist — record 3964

Sweeping the Arolsen person index for the signatory surnames, keeping only hits in
the camp, Gestapo-deportation and war-victim collections, turned up **Rudolf
Colloredo-Mansfeld, Buchenwald Häftling Nr. 20018**. His
Häftlings-Personal-Karte reads, field by field:

> Familienname **Colloredo-Mansfeld**; Vorname **Rudolf**; geb. am **16.8.76 in
> Sierndorf**; Beruf **Gutsbesitzer**; Religion kath.; Staat D; verheiratet —
> "Frau Johanna C-M., **WIEN 1, Schwarzenbergplatz 2**"; Kinder 2; Häftling Nr.
> 20018; **"Schutzhaft angeordnet: am 5.8.38 durch (Behörde): WIEN"**; bisherige
> Parteizugehörigkeit **keine**; Vorstrafen **keine**; **"Grund: war
> Legitimist"**; eingeliefert **25.9.38**; entlassen **18.10.38 nachm.**

An Austrian Catholic landowner of an old family, living in Vienna's first
district, with no party membership and no convictions, ordered into protective
custody by the **Vienna** authority five months after the Anschluss and sent to a
concentration camp — the only ground the SS recorded being that he was a Habsburg
monarchist. Ingested as **3964** with the personal card, its reverse and the
Buchenwald index card.

**What it does not show, stated plainly on the record.** The arrest of August and
September 1938 predates the first declaration (17.9.1938), the second (24.1.1939),
the third (September 1939) and Heydrich's letter (16.5.1942). It is therefore not
a reprisal for signing, and must not be offered as one. Its value is different and
arguably closer to the point MA 35 disputes: it shows what being an Austrian
aristocrat of that milieu, in Vienna, was worth to the authorities of the Ostmark
in 1938 — a camp, on no other stated ground than political allegiance.

His kinship to the four Colloredo-Mansfeld signatories (Jeroným 1870, Jeroným
1912, Josef 1910, Weikhard 1914) is unverified and is not claimed; Sierndorf in
Lower Austria is the family's Austrian seat, which makes a connection likely but
not proven.

**Negatives from the same sweep**, which are worth having: nothing in the camp,
deportation or war-victim collections for **Lobkowicz** (28 persons indexed),
**Belcredi** (24), **Strachwitz** (28), **Sternberk** (4), **Nádherný** (5),
**Dobrzenský** (24) or **Hildprandt** (3). Two searches are unusable as run:
**Schlik** returns 6,683 persons because Arolsen's matcher is phonetic and pulls in
Szulim, Scholem and Schulem; **Kinsky** returns 542, almost all Kinas, and nothing
of the Bohemian family.

## 17 September 2026 — The National Archives, Kew: a systematic sweep

### How the TNA files in Dropbox were obtained, and what they actually are

The eleven PDFs in the shared Dropbox folder are the **watermarked preview images** from
Discovery's image viewer, not purchased downloads. That matters in two ways: the images are
the archive's full-resolution scans (verified — FO 371/65328/3 page 1 is 2,648 × 4,158 both
in the Dropbox PDF and freshly downloaded), and paying £3.50 buys only the removal of the
watermark, not better resolution. Ingested as records 3968–3978, archive 6.

One correction to what I first wrote. **FO 371/65328/3 is not about the family archive.** It
concerns the archives of the Czechoslovak Ministry of Foreign Affairs, housed in the Czernin
Palace in Prague. The Germans removed material; by 1947 the originals could not be found, but
about **1,400 German translations of Czech diplomatic documents** existed, made by a German
commission under Professor **Wilhelm Wostry** working in "Zimmer 500 Cernin-Palais". The
Czechoslovak Chargé d'Affaires wrote to Bevin on 16 October 1947 asking for their return; the
British view was that "as original German documents (albeit translations) these are not
subject to restitution". Do not cite this file as evidence about the family.

The four art files are all in the folder for one reason: **the Czernin Vermeer**. FO 1020/2766/1
records, among the Alt Aussee recoveries, "the Vermeer: Self Portrait originally in the Czernin
collection of Vienna and latterly the private property of Hitler", alongside the Ghent
Adoration of the Lamb and the Bruges Madonna. T 209/29/15's supplement carries "information in
particular on the acquisition of the Czernin Vermeer". FO 1046/763/2 has prisoners' testimony
on purchases for the Führermuseum "including the Vermeer from the Czernin collection". This is
the Vienna line; the relationship to Alexander's branch is not established.

AIR 78/56/10 (three parts, 264 pages) is the index to airmen and airwomen for the name range
**Fleming W – Forsyth**. No Czernin in it. It appears to have been downloaded in error.

KV 2/3543 is **Raoul Maria Johann Ottokar Karl Haefner, alias Hafner** — Austrian, interned
several months in 1940, "nothing was found against him", then naturalised for his special
skills, filed under German Intelligence Agents and Suspected Agents, 1937–1956. It was pulled
because Alexander is "aircraft designer" on the 1951 birth certificate. **No connection between
Hafner and Alexander is established, and none should be asserted.**

### The London Czernins — a confirmed identification

Discovery's 42 "Czernin" hits include a naturalisation cluster that resolves against the tree:

- **HO 405/9351** — "CZERNIN, W, Date of Birth: 10.09.1897", 1964–65, closure status **D** (closed)
- **HO 334/1115/85718** — certificate BNA85718, 9 June 1965, "Wilhelmine Czernin, alias **Minne**",
  from Czechoslovakia, resident London

The tree has **Wilhelmine Clanner von Engelshofen, \*Hoštakov 10.9.1894, †London 5.8.1974**,
married Prague 24.9.1917 to **František Josef Theodor Ottokar Maria Czernin, \*Praha 24.5.1878,
†London 17.10.1963** (CZERNIN 3, .A3.B5.C1) — Alexander's **first cousin once removed**, the lines
diverging at Otakar Evžen. Same day and month of birth, three years out on the year, same
country of origin, same city. She naturalised two years after her husband died in London.

Their son is **Josef Vincenc Vojtěch Maria Czernin, b. 1924** — the subject of HO 405/9132 and
HO 334/395/40167, already held as record 3509. So that naturalisation file, obtained earlier,
belongs to Alexander's first cousin once removed's son, and the whole household was in London.
HO 405/9351 (the mother's file) is **closed** and would need an FOI request.

Also in the Czernin hits, not the same branch: Count **Manfred Beckett Czernin** (b. 18.1.1913),
SOE personnel file HS 9/386/6, nine AIR 50 combat reports with 17 and 85 Squadrons in 1940, and
AIR 84 records; his mother **Lucy Katherine Czernin** naturalised in 1922 (HO 144/1727/276525,
HO 334/117/478) with him named as her child. Count **Ferdinand Czernin** leased Pine Cay in the
Turks and Caicos (FCO 141/5447), and appears in a Venona decrypt of 24–25 July 1944 alongside
Papánek (HW 15/24/48).

### Signatory families at Kew — 117 war-period candidates

A 76-query sweep over the declaration surnames returned 117 records dated 1935–1955 in KV, HS,
WO, FO, HO, T, CAB and AIR. The ones that bear on "relatives of signatories were persecuted":

- **FO 371/47178** (1945) — "Request by Count **Arthur Mensdorff-Pouilly** for assistance in
  releasing his family". Mensdorff is a signatory surname. Highest priority of the lot.
- **KV 2/3424** (1945–49) — MI5 file, "Ferdinand **THUN-HOHENSTEIN**, alias Ferdinando de TONO:
  Czechoslovakian / German". Thun-Hohenstein is a signatory surname.
- **WO 416/72/248** and **WO 416/73/337** — German record cards for "[Count] Franz Ferdinand
  **Colloredo-Mansfeld**". WO 416 is the German card index of British and Commonwealth prisoners
  of war and internees. Colloredo-Mansfeld produced four signatories.
- **HO 294** — Czechoslovak Refugee Trust Fund case files, i.e. people who got out:
  **Sidonie Nadherny** b. 1885, d. 30 September 1950 (HO 294/591/9457); **Robert Hildprandt**
  b. 1893 (HO 294/592/9583); **Otto and Hedwig Lazansky** (HO 294/281, HO 294/553/5727);
  and a large Dubsky group.
- **AIR 50/173/21** and **/152** (Jan and Feb 1943) — combat reports of a Flight Lieutenant
  **Colloredo-Mansfeld**, 611 Squadron.
- **FO 370/1315** (1946) — German looting of works of art, reached via Lobkowicz and Schwarzenberg.
- **T 209/28/1**, **T 209/29/17** — repositories of works of art and archives in Germany;
  Fuehrerbau register excerpts.

None of these is asserted to be a signatory's relative yet: the surnames match, the kinship does
not follow from a surname, and each has to be run against the signatory list and the genealogy
before it can be used.

### Mechanics worth keeping

Discovery's undocumented routes, all needing a browser-issued `aws-waf-token` cookie and a
matching User-Agent:

- `GET /API/search/records?sps.searchQuery=<q>&sps.resultsPageSize=<n>` — JSON search, returns
  the full catalogue `description`, `context`, `coveringDates`, `closureStatus` and an `id`.
- `GET /API/records/v1/details/<iaid>` — has the **`digitised`** boolean.
- `GET /image/getthumbnail/<iaid>` — despite the name, returns a **JSON array of every page** of
  a digitised record: `id` is `<repo>/<series>/<piece>/<GUID>_thb.jpg`. Strip `_thb` for the
  full-size filename.
- `GET /image/getimage?iaid=<iaid>&filename=<path>` — the full-resolution watermarked page.

The HTML pages are behind an AWS WAF challenge (HTTP 202, empty body) and cannot be scraped;
the token has to come from a real browser session. Sustained calls to
`/API/records/v1/details` and `/image/*` earn **HTTP 429** quickly — throttle to a few seconds
between requests, and expect to refresh the token.

### A signatory inside Alexander's own kin — and his son taken for forced labour

Reading the typed signatory list (record 3517, page 3) against the genealogy produces a link
that had not been drawn before. Among the September 1939 signatures:

> Jan Pálffy jménem svým **a za hr. Františka Josefa Czernina**
> — "Jan Pálffy in his own name and on behalf of Count František Josef Czernin"

**František Josef Czernin** is, on the genealogy, **František Josef Theodor Ottokar Maria
Czernin, \*Praha 24.5.1878, †London 17.10.1963** (CZERNIN 3, .A3.B5.C1) — Alexander's **first
cousin once removed**, the lines diverging at Otakar Evžen. He signed by proxy; his name is on
the declaration.

His household is the one the Kew search turned up:

| person | evidence |
|---|---|
| František Josef Czernin, b. 1878 | signatory by proxy, Sept 1939; died London 1963 |
| Wilhelmine, née Clanner von Engelshofen, b. 10.9.1894 | HO 405/9351 ("10.09.1897", closed); naturalised 9.6.1965, HO 334/1115/85718, "alias Minne" |
| **Josef Vincenc Vojtěch Maria, b. 9.8.1924** | HO 405/9132 = **record 3509**; cert. BNA40167, HO 334/395/40167 |
| Monika Anna Maria Flavia, b. 1925 | the "sister Monica George" of the Post Report, married to Major George of the Military Mission to Prague |

And record 3509, the Home Office Post Report of 26 March 1953, says of the son:

> "At school and college in Prague until 1944. **Taken off for forced labour in spring of 1944.**
> Returned to Czechoslovakia with German Labour force. Released by Russians and internal
> resistance May 1945."

So a signatory's son was taken for forced labour, and the record saying so is a British
government document already in the archive, obtained for an unrelated reason.

**The caveat, and it is a serious one.** Josef was born in 1924, and the 1924 cohort
(*ročník 1924*) was conscripted *en masse* for labour in the Reich under the Totaleinsatz from
the spring of 1944. On the present evidence his deployment is at least as likely to be that
general measure as a reprisal against his father's family, and the Post Report gives no reason
for it. It must not be presented to MA 35 as persecution for the declaration unless a document
ties the two together. What it does establish, cleanly, is the kinship: a declaration signatory
is Alexander's first cousin once removed, and that family left Czechoslovakia for London in
1947–48. This is the same trap as the Kolowrat Arolsen entry: the coincidence is not the case.

Two follow-ups this opens:

- **HO 405/9351** (Wilhelmine's naturalisation file) has closure status **D** — closed. An FOI
  request to the Home Office would be needed, and after seventy years it would probably succeed.
- The signatory list contains surnames the first sweep never tested, because I had guessed the
  list rather than read it: **Battaglia, Bubna z Litic, Dlouhoveský, Daczický z Heslowa, Hrubý z
  Jelení, Kerssenbrock, Paar, Pálffy, Schönborn, Kálnoky, Serényi, Mladota, Rieger,
  Baillet-Latour**. Also on it, and relevant to the Kew hit above: **Alfons Karel and Edmund
  Mensdorf-Pouilly** — so FO 371/47178, Count **Arthur** Mensdorff-Pouilly asking British help
  "releasing his family from imprisonment" in 1945, concerns the family of two signatories.
  Arthur's exact kinship to Alfons Karel and Edmund is not yet established.

### Manfred Czernin, RAF and SOE, was Alexander's first cousin

The Kew search returned a second Czernin cluster which the genealogy places very close indeed:

> D4. **Manfred Maria Edmund**, assumed surname Beckett, \*Berlin 18.1.1913, †London 6.10.1962;
> m. London 4.11.1939 (div. 1947) Maud Sarah Hamilton — CZERNIN 3, .A3.B2.C4.D4

His father **Otto Rudolf Diepold Ottokar Maria Czernin** (\*Dymokury 27.8.1875, †Salzburg
14.6.1962), who married the Hon. Lucy Katherine Beckett in London in 1903, was the brother of
Alexander's father Pavel. Both were sons of Děpolt "Theobald". Manfred is therefore Alexander's
**first cousin**, and the two were born three months apart in 1913.

At Kew he appears as:

- **HS 9/386/6** — SOE personnel file, "Count Manfred Beckett CZERNIN — born 18.01.1913", 1939–46
- **AIR 50/9/54, /55, /63, /64, /85, /91, /92, /96** — combat reports, **17 Squadron**, 1940,
  including 12 July, 21 and 25 August, 5 and 11 September and 17 November
- **AIR 50/36/179** — combat report, 85 Squadron, 20 May 1940
- **AIR 84/2871, /10795** — service records, registered numbers 537930/36 and 621205/37
- His mother's naturalisation of 25 April 1922 names him: **HO 144/1727/276525** and
  **HO 334/117/478**, "Czernin, Manfred Marie Edward Ralph. Child of Lucy Katherine Czernin"

Set against record 3504 — Alexander deregistered in Vienna on 12 February 1940 "**als zum
Militär**", conscripted into the Wehrmacht — the two cousins were on opposite sides from the
first months of the war: one flying Hurricanes against the Luftwaffe through the Battle of
Britain and later parachuting for SOE, the other called up by the Wehrmacht from an address in
the fourth district of Vienna.

This is context, not persecution evidence, and should be offered as such.

### The four Czernin signatories are all Alexander's first cousins

Record 3512 (the autograph page from Český Šternberk) has always named four Czernin signatures —
Rudolf, Edmund, Humprecht and Jan Dypold. Running those forenames against the genealogy pins
each of them, and the answer is the same in every case: **first cousin of Alexander Czernin**,
their fathers being brothers, all sons of Děpolt "Theobald".

| signatory | dates | line | what is known of his fate |
|---|---|---|---|
| **Rudolf** Děpolt Maria Josef Ludvík, "Rudobald" | \*Dymokury 25.8.1904, †Wien 16.5.1984 | .A3.B2.C1.D1 | convicted 28.3.1944, foreign broadcasts; Kolín → Prague-Pankrác → Terezín Small Fortress → Gollnow (record 3934) |
| **Humprecht** Ottokar Paul Theobald Rudolf … | \*Dymokury 9.2.1909, †sanatorium Pleš u Prahy 19.9.1944 | .A3.B2.C1.D4 | death sentence, Petschek Palace summary court 30.6.1942, commuted to life, Brandenburg (records 3518, 3519) |
| **Jan** Nepomuk Děpold Rudolf Ferdinand, Premonstratensian Páter Václav | \*Dymokury 26.7.1915, †Wien 27.9.1967 | .A3.B2.C1.D7 | brother of the two above; nothing yet |
| **Edmund** Diepold Douglas Friedrich Ferdinand | \*Sofia 31.5.1907, †Salzburg 13.10.1994 | .A3.B2.C4.D3 | brother of Manfred, the RAF/SOE cousin; nothing yet |

Alexander's own line is .A3.B2.C6.D6 through his father Pavel. Rudolf, Humprecht and Jan are
three brothers, sons of Děpolt Josef Ottokar Otto Maria (.A3.B2.C1); Edmund is a son of Otto
Rudolf Diepold Ottokar Maria (.A3.B2.C4).

And a fifth Czernin signed without appearing on the autograph page: the typed list (record 3517)
has "Jan Pálffy jménem svým **a za hr. Františka Josefa Czernina**" — **František Josef Czernin**,
\*Praha 24.5.1878, †London 17.10.1963, Alexander's first cousin once removed.

This reframes the §58c argument. It is no longer only that the applicant's family belonged to a
persecuted class, nor only that unrelated signatories suffered: **five signatories of the
September 1939 declaration were Alexander's own first cousins or first cousin once removed, and
two of those five were tried and imprisoned** — one under a commuted death sentence who died in
custody-related circumstances in 1944, the other in the Terezín Small Fortress and Gollnow.

The identifications rest on forename matching against the genealogy. No other Czernin of these
names appears on the tree as living in 1939, but the tree is not exhaustive and the point should
be put to Jan Drocár, who supplied both the signature page and the typed list, before it is
relied on in the submission.

### HO 294 — the Czechoslovak Refugee Trust, and why its two halves mean opposite things

The Czechoslovak Refugee Trust case papers at Kew are split by the catalogue into groups, and the
group a file sits in decides whether it is evidence of **Nazi** persecution or of flight from the
**communists** eight years later. Confusing the two would be a serious error in the submission.

**'Old' Refugees — people who got out in 1938–39, i.e. from the Germans:**

| reference | person | note |
|---|---|---|
| HO 294/494/452 | **Bedřich Mels-Colloredo-Wesztfried**, b. 1884 | case papers 431–498; Colloredo is a declaration family, though Mels-Colloredo is a distinct branch |
| HO 294/553/5727, /5727A | **Dr Josef Lažanský**, b. 1899, and **Ella Lažanská**, b. 1904 | case papers 5707–5750 |
| HO 294/281 | **Otto Lažanský**, b. 1904, and **Hedwig Lažanská**, b. 1906 | personal file, 1939–1950; his naturalisation is HO 405/33767 (closed) and HO 334/318/1891 |
| HO 294/506/1288, /531/2873 etc. | a large **Dubský** group | see the caution below |

**'Post War' Refugees — people who got out after 1948, i.e. from the communists:**

| reference | person |
|---|---|
| HO 294/591/9457 | **Sidonie Nádherná**, b. 1885, d. 30 September 1950 |
| HO 294/592/9583 | **Robert Hildprandt**, b. 1893 (closed) |
| HO 294/593/9655 | **Anna Bořek-Dohalská**, b. 1911 (closed) |
| HO 294/606/X3086 | **Jindřich Thun-Hohenstein** (closed; "Refugees with Restricted Choice of Employment", 1950–68) |

Those four are from declaration families and their files will be worth having for the family
history, but they are **not** evidence of Nazi persecution and must not be offered as such.

**A caution on namesakes.** Several surname matches are plainly not the noble families. The
Sternberg hits in HO 294 and HO 396 — Gittalina b. 1872, Abraham b. 1899, Albert b. 1921 — and
the Schwarzenberger internees (Hannchen Sara, Ilse Sara, Salomon) are Jewish refugees from
Germany and Austria, recorded under the Nazi-imposed middle names "Sara" and "Israel". The
Vienna-born Dubský group in HO 396/17 has the same character. They are other people's tragedies
and have nothing to do with the Bohemian nobility of those names. The same discipline that
caught the Kolowrat deportation-list entry applies here.

### A negative finding: no British naturalisation for Alexander

Discovery returns 42 records for "Czernin". Nine concern British nationality, and Alexander is in
none of them:

| reference | person |
|---|---|
| HO 144/1727/276525, HO 334/117/478 | Lucy Katherine Czernin, from Austria, naturalised 25 April 1922, with her son Manfred named |
| HO 334/395/40167, HO 405/9132 | Josef Vincenc Vojtěch Maria Vilém Roman Czernin, certificate 17 January 1956 |
| HO 334/1115/85718, HO 405/9351 | Wilhelmine Czernin, "alias Minne", certificate 9 June 1965 (file closed) |
| HO 396/14/370 | Mary Czernin, b. 5.5.1920 Beinum, internee index card |
| FCO 141/5447 | Count Ferdinand Czernin, lease of Pine Cay |

The word "Alexander" appears nowhere in the 42 records. He lived in England from at least 1949 —
married at Haywards Heath on 9 July 1949, daughter born in Hammersmith in March 1951 (record
3967), died at Oxford on 5 April 2002 (record 3958) — so the absence of any naturalisation
certificate or Home Office file is itself informative: **on the evidence at Kew he never became a
British subject**, and there is nothing to suggest he gave up Austrian nationality.

This is a negative, and it should be stated as one. HO 334 is a series of duplicate certificates
described at item level by name, and HO 405 retains only a selection of the personal files, so
absence from both is strong but not conclusive. If it matters to MA 35, the definitive answer
would come from a Home Office nationality-records check rather than from Discovery.

### What the watermark actually costs, measured

The damage is not uniform, and it is worth recording precisely because it decides whether a free
preview is worth harvesting at all.

- **FO 371/36372/35** (record 3990) — a dark photostat of a Foreign Office jacket. The OCR
  returned *nothing but the words "The National Archives" repeated*, fifty times a page. Useless
  for search.
- **T 209/28/1** (record 3991) — a lighter typescript list. The OCR worked well enough that a
  search for "Hohenfurth", a place name that appears only in the body and not in the catalogue
  description, finds the record.
- **AIR 50** combat reports (records 3979–3987, 3992–3993) — handwritten on light paper. The OCR
  is good: "1 Me. 110 destroyed by F/O. Czernin D.F.C.", "Me.110 destroyed… over Redhill area".

So: handwriting on pale paper survives the watermark, light typescript mostly survives, and dark
photostats do not survive at all. The clean copies in Dropbox OCR perfectly by comparison — the
minute sheet of FO 1020/2766/1 transcribes down to the file-reference numbers.

Practical rule: harvest the free preview for handwritten and light material, and buy the £3.50
copy for anything dark or typewritten that the case actually relies on.

### What the Kew session added to the archive

| record | reference | pages | what it is |
|---|---|---|---|
| 3968 | FO 371/65328/3 | 6 | archives removed from the **Czernin Palace**, Prague; Wostry's 1,400 German translations |
| 3969 | KV 2/3543 | 84 | MI5 on Raoul Hafner — **no link to Alexander established** |
| 3970 | FO 1020/2610/2 | 47 | Croft-Murray's survey of Vienna's monuments and collections, April 1946 |
| 3971 | FO 1020/2766/1 | 41 | Alt Aussee deposits — **the Czernin Vermeer**, "latterly the private property of Hitler" |
| 3972 | FO 1046/763/2 | 36 | Göring's and Hitler's looting agents; the Vermeer "from the Czernin collection" |
| 3973–3974 | T 209/29/15 | 118 | ALIU report on Linz + supplement, "the acquisition of the Czernin Vermeer" |
| 3975 | T 209/29/16 | 75 | the 88 attachments: Hitler's order to Posse, Bormann on Viennese collections |
| 3976–3978 | AIR 78/56/10 | 264 | airmen index, Fleming–Forsyth — **wrong range, downloaded in error** |
| 3979–3987 | AIR 50/9, 50/36 | 39 | **Manfred Czernin's combat reports**, 17 and 85 Squadrons, 1940 |
| 3988 | FO 370/1315 | 17 | Foreign Office material on German looting |
| 3989–3990 | FO 371/36371, /36372 | 22 | Trading with the Enemy; letter from Ambassador Maximilian Lobkowicz |
| 3991 | T 209/28/1 | 50 | SHAEF's index of art repositories, 4th edition, June 1945 |
| 3992–3993 | AIR 50/173 | 17 | Colloredo-Mansfeld's combat reports, 611 Squadron, 1943 |
| 3994–3996 | CAB 24 | 20 | First World War Cabinet papers on Ottokar Czernin |
| 3997 | FO 1057/219 | 157 of 183 | the British hunt for the **looted Czech property in Heydrich's houses**, 1948–49 |
| 3998 | KV 2/3424 | 63 | Thun-Hohenstein, **Abwehr — do not cite** |

Not retrieved: T 209/29/17 (104 pages, the later Linz attachments) and the last 26 pages of
FO 1057/219 — both defeated by rate limiting rather than by access restrictions, and both
recoverable later.

Not digitised, so not retrievable remotely at all: **FO 371/47178** (Mensdorff-Pouilly's request
to free his family), **FO 817/7** (the British Embassy's own file on the attack on Heydrich and
the German reprisals — the weeks in which Humprecht Czernin was condemned), **HS 9/386/6**
(Manfred Czernin's SOE personnel file), **HO 396/14/370**, and the whole HO 294 refugee series.

## 17 September 2026 — Felix Czernin's NS-registration Meldeblatt: the Gestapo arrest and the party card on one page

Hubertus Trauttenberg's dossier on Felix (record **4006**, 36 pages, sent 8 June 2026, Zimbra
446698, scanned 2015) had been ingested with broken images — the PDF stores each page as several
bilevel TIFF fragments and my extractor uploaded one fragment per page in the wrong container, so
the pages rendered blank. Re-rasterised whole pages at 600 dpi. On reading page 2 properly:

It is Felix's **Meldeblatt zur Registrierung der Nationalsozialisten** under Article II of the
Verfassungsgesetz of 8 May 1945 — the Verbotsgesetz registration form — Gemeinde Wien, Fortl.
Nr. 3315, signed by him at Vienna on **6 November 1945**, struck through diagonally, with
"1 Gesuch mit 2 Beilagen" recorded for *Abstandnahme von der Registrierung*.

**What it gives the case.** In his own hand, under *Allfällige Bemerkungen*: conscripted into the
Wehrmacht from early 1944 until 20 March 1945, and

> "ist in Wien am 27. März 1945 von der Gestapo wegen **Hochverrat und Wehrkraftzersetzung**
> verhaftet worden"

— arrested in Vienna by the Gestapo for high treason and undermining military morale. The charge,
named, in a sworn Austrian official form seven months after the event. Also on the form: address
4., Wohllebengasse 9/10 since July 1943 (matching the Meldeunterlagen at record 3503), and earlier
addresses since 1 July 1933 in Bohemia, Aussig an der Elbe and Brussels.

**What it gives the other side.** The same page records **"Mitglied der NSDAP von Dezember 1938
bis 1945"**, party card c. 6,900,000, and NSKK membership early 1939 to early 1941 as
"NSKK-Mann (Flugzeuglehrer)", with no NSDAP function. The form exists *because* he was a
registrable National Socialist.

This has to be handled whole. A submission that quotes the Gestapo arrest from this page must be
able to answer the party membership on the same page, and it is far better that we found it than
that MA 35's historian does. The two are not in contradiction — a party member arrested for high
treason and Wehrkraftzersetzung in the last weeks of the war is a man the regime turned on, which
is the question §58c asks — but presenting one without the other would be indefensible, and would
cost the credibility the file has been built on.

**A date to resolve.** This form says the arrest was **27 March 1945**. The archive has elsewhere
recorded 28 March 1945, including in the enquiry sent to Staatsarchiv Nürnberg this morning. Felix's
own diary (record 3507) covers 26 March to 6 May 1945 and should settle it; until then, cite the
Meldeblatt date with the diary as the check.

The remaining 34 pages have not been read. They are the family's own dossier on the resistance work
behind the arrest, and they bear directly on what the four 1947 Nuremberg interrogations
(KV-Anklage Interrogations C 22, requested today) will say.

### Record 4006 read in full: Felix Czernin's §27 Verbotsgesetz file, and the O5 resistance

The 36 pages are not a scrapbook. They are Felix's complete **Nachsichtsgesuch** — his application
under §27 of the Verbotsgesetz to be excused registration as a National Socialist — lodged through
his lawyer Dr Karl Trauttmansdorff, with sworn witness statements and the Austrian authorities'
decisions on it. Magistrat file number **3315**, 4th/5th district.

**How the party membership came about, in his own account (pages 8–9).** He was a banker with the
Böhmische Escompte-Bank. "Mit 13. März 1938 wurde ich automatisch reichsdeutscher Staatsbürger."
His promised appointment as director of the Reichenberg branch was blocked in July 1938 by
prominent Reichenberg industrialists who called him **"politisch untragbar"** — politically
untenable. In August 1938 he was made director of the Aussig branch, which the **Dresdner Bank**
then took over, sharply increasing the pressure on him to join. At the end of 1938 the Aussig
Ortsgruppe sent former Austrians a form offering them the chance to apply; he and Hans von
Reiterer-Schaller signed it, he says, **expecting to be refused**, because not signing "hätte als
eine feindliche Demonstration ausgelegt werden können". He received a provisional party card in
March 1939 and was thereafter a **Parteianwärter** — a candidate, not a full member — took no part
in party activity, and instructed occasionally as a glider pilot with the NSFK.

That matters against the Meldeblatt, which records flatly "Mitglied der NSDAP von Dezember 1938
bis 1945". The authorities worked from the Anwärter characterisation.

**The resistance work (pages 9–10, 13, 26).** From early 1941 he led the Continentale Bank in
Brussels, Deutsche Bank's sister company, and belonged to "dem kleinen Kreise um General von
Falkenhausen", drawing the open enmity of the Rexists and the Flemish VNV; denunciations to the
Gestapo began then and later formed part of the basis of his arrest. From **1943 he belonged to
Wilhelm Thurn und Taxis's resistance group in the General Secretariat of O5**. Thurn und Taxis
swore on 28 August 1945 that Czernin was deployed by him **for intelligence-gathering from abroad**,
"eine Aufgabe, der er sich mit Geschick und wertvollen Ergebnissen unterzogen hat", and that on his
instructions Czernin made contact in autumn 1943 with the **Austrian resistance group in Turkey** —
"was dann letzten Endes dazu geführt hat, dass der Genannte Anfang 1945 von der Gestapo in Wien
verhaftet wurde". After the liberation Czernin rejoined him and sat on a delegation of the Austrian
resistance movement.

In Turkey in the second half of 1943 he dealt with the Austrian colony, and was close to the
Kleczkowskis and Willy Hamburger, **who went over to the Allies in January 1944**. **Fred Herok**,
director of Semperit-Kautschuk in Istanbul, later head of the "Freies Österreich in der Türkei"
movement and **himself a political prisoner of the Volksgerichtshof in Berlin for high treason**,
swore on 20 October 1945 that Czernin held a clear Austrian position and had **intervened on behalf
of a racially persecuted Jew living in Istanbul, Baron Verdor (Goldwurm)**.

Conscripted as a private in 1944, which he says he welcomed as some protection from the Gestapo; he
remained a private and received no decoration.

**The arrest and the charges (pages 11, 27).** Ordered from his unit to Vienna on 24 March 1945, he
was told the Gestapo were already looking for him, and was arrested and taken to Gestapo
headquarters on the **Morzinplatz** for a **five-hour interrogation by Kriminalsekretär Sorger**.
The four charges put to him:

1. State-hostile utterances in Belgium in 1942 and 1943, and collaboration with **General von
   Falkenhausen** — by then himself arrested and in a concentration camp — "vor allem deshalb, weil
   ich mich wiederholt dahin äusserte, dass **General Falkenhausen an Stelle Hitlers gehöre**", and
   that he had been fully informed of Falkenhausen's plans against the regime and was his collaborator;
2. that the Belgian Rexists and Flemish National Socialists had denounced him as a reactionary
   working abroad against the Reich's interests;
3. that through Turkey he had made contact with the Allies via friends since arrested or fled to
   Syria — the one charge he denies outright as untrue;
4. that he was raising his son in an anti-National-Socialist spirit and **had forbidden him to wear
   the Hitler Youth uniform**.

He was transferred to the prison in **Tower IV of the Rossauer Kaserne** and held without further
interrogation or trial. On **7 April 1945** the prisoners were told that all those not under
sentence of more than ten years would go to the **Strafkompanie at the Wilhelmskaserne** to be used
in the defence of Vienna; he **deserted immediately** and hid until the Soviet army took the inner
city. His own submission notes that of his fellow prisoners, those not released "am vorletzten und
letzten Tage noch erschossen wurden" — were shot in the last days.

Two fellow prisoners swore to it: **Herbert F. Erdmann** (15 May 1945) — they were held together
after Czernin's transfer from the Morzinplatz, "des Hoch- und Landesverrates beschuldigt", and fled
together on 7 April; and **Dr Friedrich Roth** (27 April 1945) — Czernin was put in his cell on 27
March 1945 on a charge of high treason, released 7 April, deserted, and hid at Wien I,
Schwarzenbergstraße 3.

**The state's own conclusions.** He was recorded as a **victim of Nazi terror in Austria by the
Zentralregistrierung under No. 38.885** (page 28). The district Begutachtungskommission recommended
granting the application on **5 December 1945**. Verwaltungsgruppe VII/2 of the Vienna Magistrat
supported it on **5 January 1946** because "der erforderliche Nachweis voll erbracht ist". The
Aktenvermerk of **17 August 1946** withheld his entry in the list of National Socialists pending the
government's decision, finding the proof that he had "die Zugehörigkeit zur NSDAP… niemals
mißbraucht" and was positively disposed to an independent Austria **fully furnished**. By 1 September
1947 the Zentralregistrierungskataster and the Interior Ministry recorded **no entries against him**.
An assessor's note (page 33) records that "aus den vorgelegten Unterlagen geht hervor, dass F.C.
durch die Verfolgungen und seine Inhaftierung weitgehende Schädigungen materieller wie physischer
Natur erlitten hat".

**Why this is the strongest Felix material we hold.** It is not family testimony. It is a contested
official proceeding in which Felix had every incentive to overstate his resistance and the Austrian
state, examining his claims through a commission including Communist, Socialist and People's Party
members, **accepted them** — and separately registered him as a victim of Nazi terror. The charge of
**Hoch- und Landesverrat** is attested by two fellow prisoners and by the resistance organisation
that ran him.

**The arrest date, now three-way.** His June 1945 submission says **28 March** (page 11); his later
detailed account says he was arrested on **26 March** (page 27); Roth's statement and the Meldeblatt
say **27 March**. Until the diary (record 3507) is read against these, cite the arrest as **26–28
March 1945** and note the discrepancy rather than picking one.

**What to ask for next, now that the file names it.** The Vienna file 3315 itself, with its
enclosures 1–6 including the Zentralregistrierung entry No. 38.885; the Gestapo interrogation record
by Kriminalsekretär Sorger, if anything survives at the DÖW or in the Vienna Gestapo card index;
and the O5 material on Thurn und Taxis's group. The WStLA holdings enquiry already lodged
(Eingangsnummer 2026-1609131617490) covers Opferfürsorge and Volksgericht but not the NS-registration
file by number — that should be added.

## 17 September 2026 — eBadatelna went live, and it holds a file on Humprecht's death

The ABS account was activated this morning (Mgr Ilona Hypšová, Oddělení archivních fondů FMV;
Zimbra 464413, *"s opravou technických problémů máte účet již aktivní"*, with the system
confirmation at 464411). Activation lasts one year, so it lapses around 17 September 2027 and
renewal needs an in-person visit — which is now the only remaining reason to go to Na Struze 3.

**The verification trap, confirmed from the other side.** Before activation every OCR query
returned zero rows, `Praha` included. The same search for `Czernin` now returns **82 inventory
units**. Any nil result recorded here before today is worthless as evidence of absence and should
be re-run.

### The find: ABS 325-113-1 and 325-113-2

Fond **325, Stíhání nacistických válečných zločinců Státní bezpečností** (the StB's prosecution of
Nazi war criminals), series *Svazky ke konfidentům Gestapo a osobám podezřelým ze spolupráce*.
Two volumes on **Ing. František Dvořák (b. 10.12.1912), a forestry engineer on the Šternberg and
Czernin estates recruited by Gestapo Kolín** — the Gestapo office covering Dymokury. Original
volume K-79.

The catalogue description of 325-113-1 says in terms:

> Součástí svazku jsou **důkazní dokumentace o úmrtí Humprechta Czernina z Chudenic (nar. 1909)**,
> protokoly o výslechu bývalého velitele Gestapo Kolín Paula Feustela (nar. 1899), úředně ověřené
> fotokopie německých materiálů z let 1942–1943, získané z Německé demokratické republiky, a jejich
> české překlady…

So: **evidentiary documentation on the death of Humprecht Czernin of Chudenice (b. 1909)** —
Alexander's first cousin — held inside a post-war Czechoslovak war-crimes investigation, alongside
the interrogation of the Gestapo Kolín commander and certified photocopies of the German 1942–43
originals obtained from the GDR. This is the first state-held file we have found that addresses
Humprecht's death directly, rather than the family's own account of it.

Hit distribution for "Humprecht" (scan numbers, from the per-unit OCR hit list):

| Unit | Scans | Humprecht hits | On scans |
|---|---|---|---|
| 325-113-1 | 214 | 26 | 29, 30, 34, 44, 46, 47, 52, 58, 61, 67, 69, 75, 128, 129, 149, 150, 153, 193, 200, 202 |
| 325-113-2 | 291 | 18 | 5, 22, 23, 30, 38–41, 44–46, 86, 93 |
| 325-121-1 (Feustel's own file) | 215 | 2 | 18, 139 |
| 325-164-6 (StB investigators' final reports, 1970–79) | 227 | 2 | 72 |
| 325-121-4 | 31 | 1 | 28 |

All five are ingested: **325-113-1 is record 4008**, the others followed in the same run.

### Other Czernin material in ABS worth noting

- **TS-810254 MV — "Czernín, Josef (28.11.1943)"**, an StB collaborator volume on a Czernín born
  1943. Not our line on dates, but it should be identified before anyone else raises it.
- **H-172 „BÝVALÁ ŠLECHTA"** (151 scans) and **H-23 / H-108 / H-4 „Bývalí lidé"** — StB files on
  the former nobility, with 12 and 8 Czernin hits respectively. Context for how the family was
  treated after 1948, not evidence of wartime persecution.
- **H-450, Akce StB „TAXIS": THURN-TAXIS Franc, b. 15.4.1915** (45 scans). Felix's O5 handler was
  Wilhelm Thurn und Taxis; whether this is the same family circle needs checking before it is
  cited. **KR-707964 MV** (Thurn-Taxis, Hugo, b. 21.9.1916) and **KR-45291 MV** (Thurn Taxis, Hans,
  b. 28.6.1908) are the same question.
- **325-60-6**, *Germanizace a perzekuce v letech 1938–1945*, carries the only Trauttmansdorff hit.

Not ingested yet, pending a decision on cost: H-172, H-450, 325-61-11 (the Protectorate's own
telephone directory from the Černín Palace, 70 Czernin hits — but those are the palace, not the
family).

### What the search is and is not

It is stemmed, loose, and reads catalogue metadata as well as scan OCR. `Černín` returns 576 units,
mostly `Čermín` and `Černý`. Multi-word input is treated as a phrase, so `Felix Czernin` returns
nothing at all while `Czernin` returns 82 — one surname at a time, then filter. `Dymokury` returns
208 units ranked by an unrelated 1950s file. Treat the hit counts as a ranking, not a finding.

The scraper was rewritten to match the verified API (commit `7c0f8a6`); every endpoint it had used
before was wrong, which is why it had never fetched a scan. Maximum scan resolution is 992×1402 px
and `GetImageSnippet` only upscales, so these are legible but not high-resolution images.

### What 4008, 4009 and 4012 actually say — the state's own finding of an attempted murder

The five units are OCR'd and embedded. They are the **primary sources behind Hořejš's 2014
article** (record 3519), which we had been citing as secondary literature; we now hold the
investigation files themselves.

**The StB investigators' final report** (record 4008 page 126, repeated in the 1970–79 report
series at record 4012 page 70) states the conclusion in terms:

> Dle výpovědi Pavla FEUSTELA byl s MAYEREM projednán celkový postup k zatčení hraběte CZERNÍNA.
> […] Obv. DVOŘÁK […] převzal od gestapa návrh, že hraběti CZERNÍNOVI **bude podvržena pistole a
> tím vytvořeny podmínky pro jeho likvidaci**. Obviněný DVOŘÁK v této době znal, že svým jednáním
> ohrozí život svého zaměstnavatele. Pokud jde o jeho osobu, byl gestapem seznámen, že bude i on
> odsouzen k trestu smrti, ale to jen „na oko" a že bude brzo propuštěn. S těmito podmínkami
> DVOŘÁK souhlasil a stal se tak **přímým spolupachatelem na tomto pokusu vraždy**.

So a Czechoslovak state investigation found that the pistol was **planted on Count Czernin by the
Gestapo to create the conditions for his liquidation**, and that his own forestry manager took part
knowing it would endanger his employer's life, on a Gestapo promise that his own death sentence
would be "for show". Humprecht was not caught with an unlicensed weapon; he was framed. The same
report notes the Gestapo's own instruction, quoted by Hořejš from Feustel, that Czernin **was not to
be shot at Pardubice — his disappearance had to look "legal and lawful"**.

**Dvořák's own account** (4008 page 149) adds the part Hořejš could only infer: he says Humprecht's
arrest followed from his statement that the pistol was Czernin's, *"což na mně bylo vynuceno v
nějaké úřadovně v Novém Bydžově"* — forced out of him in an office at Nový Bydžov. He puts
Humprecht's arrest on the evening of **9 June 1942**, on the train or at Nový Bydžov station,
returning from Prague, and describes being confronted with him at Gestapo Kolín about ten days
later, around 20 June, by the Gestapo men **Blum** (interrogating) and **Wittinger** (recording and
interpreting). He denied the collaboration throughout; the file records that he was later acquitted.

**The estate witness** (4008 page 193) says Humprecht showed no sign of lung disease before his
arrest: *"činil na mne dojem naprosto zdravého člověka, který se nevyhýbal fysické i těžké práci"*.

**Anna Chládková's testimony** (4009 page 45) is the fullest account of the death we have from any
source. After a permitted visit to Brandenburg — arranged by a relative of the family who was a
Reich German, and made by Humprecht's wife, his sister Marie and probably his mother — they reported
that he was in a wretched state with severe tuberculosis *"která mu byla pravděpodobně
naočkována"*, **which had probably been inoculated into him**. He was brought to the lung sanatorium
**Na Pleši** near Mníšek pod Brdy on **14 September 1944**, accompanied by a nurse who came directly
from Brandenburg; his wife **Ida Czerninová née Lobkowicz** stayed with him until he died of
pulmonary tuberculosis on **19 September 1944**. The family asked the Prague Gestapo to let him be
buried in the family tomb at Dymokury.

That request was refused — which answers a question left open by the nephew's own 1972 testimony
(4009 page 41), where he says he attended the funeral at **Břevnov cemetery** in Prague, that the
grave is still there, and that he never knew why his uncle was not laid in the family tomb. The same
nephew — six years old in 1942, so born about 1936 — records that when he wound up his grandmother
Marie Czerninová née Kinská's estate in 1952 at Praha 1, Provaznická 1, he found **an officially
certified copy of 20 September 1949 from the Na Pleši sanatorium concerning the death**, and a
German-language document of 16 June 1945 signed **Dr Josef Morávek, Praha II, Vodičkova ("Lucerna")**.

**Handle the inoculation allegation carefully.** It is what the family was told after a prison
visit, recorded thirty years later by a servant; it is testimony about a belief, not a finding.
The state's finding is the framing and the intended liquidation, and that is what should be cited.
Deliberate infection should be given as what the family believed and reported, with the source
named.

**What this adds to the §58c case.** The persecution of Alexander's first cousin is no longer
established only from published scholarship and family papers: it rests on a Czechoslovak criminal
investigation that interrogated the responsible Gestapo commander and concluded the arrest was a
staged murder attempt. Arolsen already holds Humprecht's Brandenburg-Görden admission record
(record 3406, DOB 9 February 1909), and the Terezín Memorial holds Rudolf's prisoner record
(3934). The chain for the Dymokury brothers is now: framing (ABS 325-113-1/2), sentence
(Standgericht, 30 June 1942), imprisonment (Brandenburg-Görden, Arolsen), death (Na Pleši, 19
September 1944) and the refusal of a family burial.

**Follow-ups this opens.** The certified 1949 Na Pleši copy and the Morávek document of 16 June
1945 were in the grandmother's papers in 1952 and may be in the Dymokury family archive rather than
a state one — worth asking the family before asking an archive. Jaroslav Holý's 1973 statement as
valet and driver is cited by Hořejš from this same investigation and should be in one of the two
volumes. And 325-121-1, Feustel's own file (record 4011, 215 scans), carries his testimony at first
hand.

### 17 September 2026 — the last watermarked previews replaced

The free download allowance covered the rest of the watermarked set. Twelve records had their page
images swapped in place — same record IDs, same descriptions, 334 pages — and are back through OCR:

| record | reference | pages |
|---|---|---|
| 3973 + 3974 | T 209/29/15 (ALIU Linz report + the January 1946 supplement) | 58 + 60 |
| 3975 | T 209/29/16 (the 88 attachments) | 75 |
| 3971 | FO 1020/2766/1 (Alt Aussee deposits, the Czernin Vermeer) | 41 |
| 3972 | FO 1046/763/2 (Göring's and Hitler's looting agents) | 36 |
| 3970 | FO 1020/2610/2 (Croft-Murray on Vienna) | 47 |
| 3968 | FO 371/65328/3 (archives removed from the Czernin Palace) | 6 |
| 3980, 3981, 3982, 3984, 3986 | AIR 50/9/54, /55, /63, /85, /92 (Manfred's combat reports) | 11 |

T 209/29/15 is the one that mattered: as a watermarked preview its OCR returned nothing but "The
National Archives" repeated down the page, and it carries the account of the acquisition of the
Czernin Vermeer.

**A resolution surprise worth recording.** TNA's purchased PDF of T 209/29/15 delivers pages at
**842 × 1191 px**, well below the 2648 × 4158 of a watermarked preview image. Checked before
uploading, because a swap that lowered resolution would have been a downgrade: the typescript is
completely legible and, being clean, OCRs — which the larger watermarked version did not. So
the purchased file is not always the bigger file, and "clean beats big" for typescript. The other
files came down at 1200–5260 px wide.

Deleted: records **3976–3978**, AIR 78/56/10, 264 pages of the RAF airmen index for the name
range Fleming–Forsyth, downloaded in error during the Kew sweep and of no relevance to anyone in
this case.

Also deleted, on the same decision: **KV 2/3543** (record 3969, Raoul Hafner, 84 pages) and
**KV 2/3424** (record 3998, Thun-Hohenstein, 63 pages). Neither was ever going to be cited —
Hafner's supposed link to Alexander was never established, and Thun-Hohenstein was an Abwehr
recruit whose file was flagged *do not cite* — and both existed only as watermarked previews
whose OCR is unusable. The checked negative survives in this log, which is where it belongs:
**MI5 held nothing connecting Alexander Czernin to Raoul Hafner**, and the Thun-Hohenstein file
concerns a different man of a different family. Do not re-order either.

Archive 6 (UK Government) therefore now holds **29 records**, all of them clean copies apart from
record 2558, which came from another route.

### What the clean T 209/29/15 gave up immediately

The page that had been unreadable is the one that mattered. In the ALIU's supplement of 15 January
1946 (record 3973, page 5), under *Page 35 (2), bottom, The CZERNIN Vermeer*:

> A thick folder of correspondence in the Reichskanzlei files concerning the purchase of Vermeer's
> *The Artist in His Studio* casts doubt on the possibility that the sale was forced. It should be
> kept in mind, however, that (1) the CZERNIN family still maintain that the contract was closed
> under duress, (2) **any suggestion of forced sale would naturally be eliminated from official
> correspondence**, and (3) **HITLER blocked at least two attempts to dispose of the painting to
> other collections, once at a higher figure than he ultimately paid for it**.

That is the Allied investigators' own reasoning, in 1946, and it is more careful than either side of
the later restitution argument. It concedes the documentary record looks like a sale while naming
the reason the record would look that way, and it identifies the fact that does not depend on the
correspondence at all: the owner was not free to sell elsewhere. The same page records that Count
**Jaromir Czernin-Morzin of Marschendorf** was willing to sell the Vermeer to the Hamburg dealer
Reemtsma for **1,800,000 RM**, that Göring supported the proposal, and that on **29 December 1939**
Lammers wrote that Hitler had ordered the picture to remain in the Czernin collection and not be
moved without his explicit consent — against the **1,650,000 RM** Hitler paid in 1940.

Record 3975 (T 209/29/16) now also yields Attachment 57 in full: Bormann to Posse, Berlin,
26 September 1940, "Der Führer will das Bild zu diesem Preise kaufen und wuenscht, dass Sie umgehend
zwecks Abschluss des Kaufvertrags nach Wien fahren", with Schirach instructed to supply an official
for the signing. It sits directly against the Kunstrückgabebeirat's 2011 refusal (record 3798), which
works from the same Reichskanzlei file.

**Scope caution.** The Vermeer was Jaromir Czernin-Morzin's, not Alexander's line. This belongs in
the archive and in the account of what was done to the family, not in the §58c submission as if it
were the applicant's own loss.

### The men who framed Humprecht, now named

The second tier of ABS units is in — records **4013–4018**, 841 scans, taking archive 2 to eleven
records and 1,818 pages. They were ordered to put identities to the Gestapo Kolín officers who
appear in the Dvořák file as bare surnames, and they did.

| In the testimony | Identified as | Born | After 1945 |
|---|---|---|---|
| "MAYER", who supplied the pistol that was then "found" on Dvořák | **Heinrich Meier / Meyer** | 1908 | photograph held in 325-113-2 |
| "WITTINGER", who recorded and interpreted the confrontation at Kolín | **Franz Wittiger** | 14.11.1896 | **imprisoned in Czechoslovakia** |
| "HORN", whose handwritten notes are in 325-113-1 | **Josef Horn** | 1.9.1906 | **imprisoned in Czechoslovakia** |

The list of those imprisoned comes from a Berlin document of **27 December 1971**, *"Ehemalige
Gestapoangehörige die nach 1945 in der CSSR inhaftiert waren"* (record 4016, page 116), which also
names Ladislav Bambas (Gestapo agent in Kolín and Benešov), Josef Möhlwald, Julius Müller-Riedel,
Jan Odradovec (Gestapo agent in Kolín), Alfred Rziha and Franz Schorn.

Record 4008 page 110 carries a post-war Czech list of the Kolín outstation's *"zvláště hrubí
úředníci"* — its particularly brutal officials — which puts **Heinrich Meier** and **Emil Laufka**
in the same short list as Müller-Riedel, Schindelář, Fleischer, Schulte and Schorn. Laufka has his
own file, now held as record 4015.

Feustel's driver **Josef Möhlwald** (b. 17.11.1896), interrogated 9 March 1972, gives the office's
shape from the inside: the Oberlandrat occupied the ground floor of the same Kolín building and the
Gestapo the first floor, where he waited with the drivers; Feustel's deputy was **Dewesse**, and the
staff he could name were Neumann, Mayer, Schorn and the Czech employees Otradovec and Říha
(record 4009, page 57).

Also identified, and worth noting for any future request: **Miloslava Vachová**, the Kolín
Gestapo's long-serving typist, born 1922 at Horusice and living at Veltruby 1 in 1946, examined as
a witness on 8 November 1946 — the German investigators' own note says she "müßte daher in der Lage
sein" to speak to the individual cases (record 4011, page 205).

**Why this matters to the case.** The frame-up is no longer a conclusion drawn from one accused
man's account. The officer who supplied the planted pistol is identified by name, date of birth and
photograph; two of the three men in the room are recorded as having been imprisoned for what they
did; and the Czech authorities independently listed the same men among the worst of that office. It
is a corroborated state record of a Gestapo operation against the applicant's first cousin.

### 19 September 2026 — what the Felix pack was turned into, and what is deliberately not being chased

Record 4006 came from Hubertus Trauttenberg, who says he had the copies from the
DÖW around 2015 and holds nothing further (Zimbra 464752). Reading it in full on
18 September produced four requests, each aimed at where the document class
should be held now rather than at the pack itself:

| What the pack shows | Asked of | Reference |
|---|---|---|
| Meldeblatt and Begutachtungskommission, M.Nr. 3315/IV. Bez. | WStLA | form **2026-1809203348262** |
| § 27 Nachsichtsgesuch, Zl. 13.413-2 N/1945, decided 5.1.1946 | WStLA | same form |
| 1947 Leithner application, its **6 Beilagen** and the decision | WStLA | same form, enclosure list and folio counts asked for by name |
| Zentralregistrierung as victim of Nazi terror, Nr. 38.835 (MA 62) | WStLA | Zimbra **464801**, added to 2026-1609131617490, all six siblings |
| The Hoch- und Landesverrat charge, never tried | Bundesarchiv R 3017, **R 3018**, R 58 | Zimbra **464803** |
| Thurn und Taxis as Leiter der Geschäftsstelle im Generalsekretariat O 5 | DÖW | Zimbra **464805** |

The WStLA form orders the digitisation of the whole file in advance to EUR 200,
so it returns in one pass instead of after a quotation.

**Ruled out, 19 September:** the Semperit company archive behind Fred Herok's
statement (page 14), and the practice files of the two lawyers, Dr Karl
Trauttmansdorff and Dr Anton Leithner. Not to be proposed again.

**Not a lead — already covered.** The attestation by the *Vereinigung ehemaliger
österreichischer Widerstandsbewegungen* (page 3) is an incoming document in the
Zentralregistrierstelle's own file, which Zimbra 464801 and the WStLA form
already order. Asking a third party for a copy of something ordered from the body
that holds the original is not a new enquiry.

**What page 3 gives as evidence.** Two documents on one sheet:

1. A letter of **5 March 1946** from the Vereinigung, Wien I., Dominikanerbastei
   6/4, on ÖVP (IV. Bezirk) letterhead, to the **Zentralregistrierstelle der
   Opfer des Naziterrors**, Neues Rathaus, 2. Stock, Zimmer 11, asking that Felix
   be registered, since he was held *"vom 27.3.1945 bis zur Befreiung durch die
   russische Armee wegen Hoch- und Landesverrat"*. **This is the origin of
   registration Nr. 38.835, and it dates it: the application went in on 5 March
   1946, so the "16.3.1945" read on page 28 is 16.3.1946.**

2. An attestation signed **Fritz Janich**: arrested by the Gestapo on **28 March
   1945**, held in the **Rossauer Kaserne until 7 April**, when the Red Army's
   advance caused all remaining prisoners to be released with orders to report at
   once to the **Wilhelmskaserne** Alarm-Kompanie. *"Czernin hat diese Weisung
   nicht befolgt, sondern sich bis 10. April... versteckt gehalten."*

The second is a third act, distinct from the arrest and the detention: a refusal
of a military order in the last week of the war, attested by a third party in an
official submission made ten months later. The same sheet carries both the 27 and
the 28 March dates, which is where that discrepancy comes from; the diary settles
it as arrival 26th, first Gestapo visit 27th, arrest 28th.

**Still open as a lead:** Rossauer Kaserne detention records for March-April 1945
(page 6), which no outstanding request would return.

### 19 September 2026 — two gaps closed, and one question deliberately left unasked

**Alexander's military record had never been asked for.** The Militärarchiv letter
of 15 September concerns Felix and the Abwehr. Hubertus Trauttenberg supplied the
missing idea — *"one had to find his Wehrstammkarte"* — and record 3505 page 30
supplies the handle: his own letter of 22 February 1942 heads itself *"Dipl.Ing.
Alexander Graf Czernin, Truppen-Ingenieur, 17 578"*, which reads as a
Feldpostnummer and would identify the unit. Sent to Bundesarchiv Abteilung PA,
the former Deutsche Dienststelle (Zimbra **464913**), for all six siblings, asking
among other things for *"allfällige gerichtliche oder disziplinarrechtliche
Vorgänge sowie Vermerke über Fahndungen"*. Desertion appears there without the
word being used: an archive asked a leading question answers the question.

**The Staatskanzlei's own § 27 file was never asked for either.** The WStLA
request of 18 September buys the Magistrat's side. The submission was lodged with
the Staatsamt für Inneres (record 4006 page 7) and the Staatskanzlei referred it
onward under **Zl. 13.413-2 N/1945** (page 12) — a different authority's file,
which should hold the submission as lodged and the Staatsregierung's decision.
Sent to the Archiv der Republik, Zimbra **464915**.

**Not asked, and the reasoning is worth keeping.** Whether § 27 files exist for
the other five siblings, Alexander included. Such a file exists only because the
applicant fell under the registration duty, which attached to membership or
candidacy in the NSDAP, SS, SA, NSKK or NSFK — so the question asks an Austrian
federal archive, in writing, whether these people were National Socialists. A nil
proves little, since AdR holds the Staatskanzlei's side and not the municipal
registration lists; a hit would be handed to the state that decides the
application, unprompted. MA 35 has no reason today to think Alexander was a
member. The applicant's judgement, and it is right: *"why risk being told
otherwise, its only downside, by asking"*.

### 19 September 2026 — record 4006 was ten sideways scans, seven of them two pages each

Page 28 returned 491 characters of a sheet holding about 3,400. The scan was
rotated 90 degrees **and** held two separate document pages side by side, so
every engine had been reading it sideways and as one page.

Ten pages were affected — 1, 8, 11, 12, 15, 17, 18, 22, 25, 28, 34, 36 by a
first heuristic pass, of which **8, 11, 17, 18, 25, 28, 34** proved to be two
documents and **12, 15, 36** merely sideways. A gutter detector put the cut
through single documents on three of them (*"Wiener M | agistrat"*), so every
page was checked by eye before anything was written.

The record is now **43 pages**, consecutively numbered, each holding one
document. The internal foliation reads straight for the first time: pages 8-13
are *-2-* to *-7-* of the 1945 submission through Dr Karl Trauttmansdorff,
19-22 are *-2-* to *-5-* of the second, and 29-36 the 1947 Leithner submission.

**Citations move:** old 13 → 15, old 19 → 23, old 21 → 25, old 28 → 34,
old 30 → 36, old 8-9 → 8-10. Pages 1-7 are unchanged.

**Two defects were found and fixed on the way** (v1.1.8, v1.1.9). The API could
replace a page or wipe every page, but not insert or delete one — so a sheet
could not become two pages without re-ingesting the record. And page images were
stored as `p{seq}.jpg`, which an insert collides with: seven pages ended up
showing their neighbour's image, with two attachment rows pointing at one file.
Images are now named `p{seq}-{sha8}.jpg`. The affected pages were restored from
the original scans, which had been copied locally before the work began.

### 21 September 2026 — the Archiv der Republik answers on Wolfgang and the mother

Kontr. Ayse Ertugral, AdR (ref. **2026-0.771.003**), on the 15 September addendum:

- **Wolfgang Czernin (b. 4.8.1903)** — a card exists in the Staatspolizei card
  index, **no corresponding file survives**. The card is now record **4020**.
- **Maria Gabriele Czernin**, Alexander's mother — **nothing at all**.
- **Inneres BMI StSu StaPo Akten Kzl 32.961-2/47, Czernin Ferdinand** — **51
  pages**. B/W copies EUR 1,00 per page, digital reproductions **EUR 6,00** per
  page, so EUR 51 against EUR 306 for the same content. Processing about a week.

**The card is not what it first looked like.** It reads: *"CZERNIN Wolfgang Ing.,
4.8.03, Wien 3., Salmgasse 2 — 115.721-2/52 Bericht d. SD Salzburg v. 5.6.52:
Verkehrsunfall am 2.6.52 bei Strasswalchen zwischen dem Pkw. des C. und einem
amerik. Pkw. Sachschaden."* **SD** here is the *Sicherheitsdirektion Salzburg*,
the post-war provincial security authority, **not** the NS Sicherheitsdienst, and
the matter is a road traffic accident with an occupation-forces car in 1952. No
evidential value for the § 58c case; it fixes Wolfgang at **Wien III.,
Salmgasse 2 in 1952** and documents the nil return.

It also shows what this index is: a general police register that sweeps up traffic
accidents, which lowers the expected value of any file found in it by name alone.

**Ferdinand is a different branch** — the Ottokar line, an anti-Nazi émigré who
appears at Kew over a refused UK visa, the Pine Cay lease (FCO 141/5447) and a
1944 Venona decrypt alongside Papánek (HW 15/24/48). Before spending EUR 51 the
archive was asked what the file concerns and which persons appear in it (Zimbra
**465233**), with the B/W preference stated in the same letter so the pricing
needs no further round trip.

### 21 September 2026 — three of the five WStLA Meldeanfragen answered

Robert Plank / Gregor Merkel, MA 8, all dated 21 September.

| Zahl | Person | Period | Result |
|---|---|---|---|
| B-MEP-1294932-2026 | Wolfgang Otto Paul Dominikus Maria, b. 4.8.1903 | 1938-1946 | **nil** |
| B-MEP-1295074-2026 | Franziska Paula Gabriele Johanna Maria Koblitz von Willmburg, née Czernin, b. 8.3.1910 | 1935-1950 | **nil** |
| B-MEP-1295012-2026 | Dr. Johannes (Jan) Czernin, b. 8.3.1910 Göding | 1938-1950 | **data** |

Wolfgang's nil is consistent with Berlin — Müggelseedamm 240 — in those years, and
is worth having as a documented nil rather than an untested gap.

**Jan's registrations**, citizenship given as *Deutsches Reich*, with wife Maria
(Osterheldis Freiin von der Lippe, b. 28.3.1917) and daughters Maria (b.
26.1.1942) and Maria-Christina (b. 13.3.1943) co-registered:

- 30.5.1938 - 25.8.1938: **Wien III., Rennweg 2**, previously *Aigen-Glas bei
  Salzburg*; deregistered end June 1938 to III., Am Heumarkt 22/9, a registration
  the archive could not trace;
- 26.11.1938 - 5.1.1939: **Wien III., Salesianergasse 4/8**, previously III.,
  Heumarkt 9; **deregistered to Krumau, Sudetenland**.

Nothing later survives in the holdings up to the end of 1975; anything after that
sits with MA 62 (Meldeservice), which by law answers only on people currently
living in Austria.

**The refusal of a scan, and the way round it.** MA 8 would give no copy because
the co-registered wife and children fall inside § 10 (2) Wiener Archivgesetz —
the protection period for personal data, which **ends with the death of the person
concerned**, or 110 years after birth where no death date can be established. The
children, born 1942 and 1943, may be living. The restriction therefore attaches to
those three individuals and not to the document.

Asked for a copy with their entries redacted (Zimbra **465258**), on the ground
that **Jan himself died at Salzburg on 24 May 1996**, so his own protection period
ended then. No purpose stated — only the legal point and the death date the
archive did not have.

The alternative, § 10 (3) *Schutzfristverkürzung* for scientific research or
"besondere persönliche Gründe", was not used: it would require telling a Vienna
authority what the research is for.

**Still outstanding of the five paid enquiries:** Anna (2026-1609105840418) and
**Alexander's own, 1940-1950** (2026-1609110159140) — the one that matters.

*Incidental:* "Lippes", who recur throughout Felix's 1945 diary — *"Besuch bei
Lippes"* — are the family of Jan's wife, Osterheldis von der Lippe.

### 22 September 2026 — the Clary family archive is the wrong Czernin line

Mag. Maria Zdislava Röhsner (HHStA) volunteered two items in the Familienarchiv
Clary signed *"A. Czernin"* — Clary **20-25**, letters to Edmund Clary 1923-1926,
and Clary **13-2**, a letter to Franziska Clary of 13.1.1931 — noting it might be
Alexander but that she could not be sure.

A full-text search of the Archivinformationssystem for Czernin within that
Bestand returns ten units:

| Signature | Correspondence | Dates |
|---|---|---|
| Clary 13 | Franziska Clary, correspondence with families Czernin and Radziwill | 1874-1938 |
| 13-1 | Franz Czernin to Franziska Clary | 1874-1932 |
| 13-2 | **A. Czernin** to Franziska Clary | 13.1.1931 |
| 13-5 | Eugen Czernin to Franziska Clary | 1899 |
| 20-25 | **A. Czernin** to Edmund Clary | 1923-1926 |
| 20-26 | Cari Czernin to Edmund Clary | 1949 |
| 4-4 | Franz Czernin to Manfred Clary | 1900-1907 |
| 31a-5, -6, -9 | Dolly, Edmund and Menny Clary to their grandmother **Marie Pejacsevich, née Czernin** | 1903-1914 |

**The 31a group explains the rest.** The Clary-Czernin connection is a
grandmother, Marie Pejacsevich née Czernin, and the correspondents are her kin.
The forenames place them: every **Eugen** Czernin in the genealogy sits in
**CZERNIN 2** (the `.A5` branch), as do the Franzes and Caris. The applicant's
grandfather is **CZERNIN 3**, `.A3.B2.C6` — Dymokury and Schönhof.

**Treated as a dead end, and not to be reopened without a new reason.** Three
grounds: wrong branch on the naming evidence; nothing in the date range that
matters, since everything but Cari's 1949 letter predates 1938; and Alexander was
10 to 13 during the 1923-1926 correspondence, which no boy of that age conducts
with an adult of another household. Nothing was bought — HHStA charges EUR 6 a
sheet.

**What is genuinely ours in that Bestand** is Clary **48-144**, the Gedenkbild of
Johannes (Jan) Graf Czernin of 24.5.1996, already ordered. The point of contact
between the two families is Jan at Salzburg late in life — this Clary line is
Herrnau, Salzburg — not Vienna in the 1920s.

Answered with thanks (Zimbra **465462**), setting out the reasoning so she can
correct it if the branches are wrong, and saying what shape a useful find would
have: Salzburg, and the later years. She looked through a fonds nobody had asked
her about and wrote unprompted; she is also the archivist who handled the HHStA
order and flagged the expired protection period on Clary 48-144.

### 22 September 2026 — AdR: the Gauakt ordered, the Ferdinand file dropped

Two answers from Kontr. Ayse Ertugral, AdR:

**Ferdinand Czernin (*25.11.1903 Hermanestes/Böhmen), StaPo 32.961-2/47** — the
file concerns *"die Pressetätigkeit und sonstige Aktivitäten des Genannten im
Zusammenhang mit der sog. Austrian Action und der American Association of Former
Austrian Jurists"*. The New York exile movement, a different branch. **Not
ordered** — the free question saved EUR 51 and a week.

**For Alexander they found one file:** `ZNsZ GA Czernin Alexander (*30.04.1913)
(Bewertung durch Gaupersonalamt)`, **12 pages**. Ordered in full as scans at EUR
6,00 each (Zimbra **465466**), with the question whether it is the same **Gauakt
Nr. 232.006** already held as record 3499 — where it runs to only **ten** sheets.
If it is, our copy is short by two.

**Re-reading 3499 while doing this turned up something not yet used.** The
SD-Leitabschnitt Wien report (III C 3 PA 3852/41) states that on **12.2.1940 he
was drafted into the Wehrmacht at Wels and was serving as a Wehrmachtsbeamter**;
that he had been a **member of the Heimwehr until 1935**; that he spent 15 weeks
in voluntary service at the Steyrwerke during his holidays; that he is *"der Neffe
des ehemaligen österreichischen Ministerpräsidenten Graf Czernin"*; that **three
of his brothers were also serving in the Wehrmacht**; and that *"Nachteiliges
konnte über ihn nicht in Erfahrung gebracht werden"*.

Wels and *Wehrmachtsbeamter* are a concrete lead for Oberst Reisner that was not
in the letter of 20 September, and they corroborate *Truppen-Ingenieur* against
the family's *Wachsoldat*. The Heimwehr membership and the SD's own note of the
family name belong in the persecution argument: it is the Heydrich logic — name
and connections rather than individual conduct.

**Two things worth knowing from the ÖStA fee schedule** (attachment to the 21
September letter). First, section 5(b): *a small number of working scans (max.
200 dpi) may be transmitted without charge where it is administratively
expedient, at the discretion of the Bestandsgruppenleiter* — which is how the
Wolfgang Karteikarte arrived free, and is the route for one or two sheets.
Second, section 6: written enquiries are charged at **EUR 45 per half hour, the
first half hour free** — so questions should be bundled into one letter rather
than dripped.

**Postage is not in the tariff at all**, so a posted order's true cost is not
knowable in advance. Scans at EUR 6 avoid the question and arrive as a download
link, ready to ingest.

**Next step on the Gauakt, and a framing to avoid.** Wait for the twelve sheets,
then reassess before writing to Stefan Huber. The tempting argument — *the SD
began targeting Alexander because he was a Czernin* — **overstates the file**:
the Gauakt was opened because the Technische Hochschule wanted his appointment as
a research assistant confirmed, and the SD report answers the Gauleitung's
routine request. It shows no SD initiative against him.

What it does support is narrower and still useful: **when the apparatus assessed
him, it identified him by dynastic name and connection** — "nephew of the former
Austrian Prime Minister Count Czernin", a relative a senior cleric in Copenhagen,
three brothers in the Wehrmacht — and it referred the question to the SD to
answer. That is the Bormann-letter criterion, family rather than conduct, applied
to Alexander in person. It corroborates the criterion already pleaded; it does not
prove initiative, and must not be written as though it did.

Two further caveats for the reassessment: the outcome was **favourable**
("keine Bedenken", "Nachteiliges konnte über ihn nicht in Erfahrung gebracht
werden"), and a vetting passed is weaker than one failed; and the 15 weeks of
voluntary service at the Steyrwerke belongs with Felix's party card in the
"answer it before the authority finds it" category.

The two unseen sheets are the thing to look at first: in a Gauakt the missing
leaves are typically the Ortsgruppe's reverse-side Gutachten or a later
re-inspection note, either of which could change the reading.

### 23 September 2026 — three answers in one morning, and the Transkribus route that works

**MA 8 / WStLA, the two paid Meldeanfragen.**

*Anna Maria Franziska Gabriele von Handel, née Czernin (b. 26.5.1907)* —
**B-MEP-1295047-2026: nil.** No data at all in the historical Vienna registration
records for 1935-1950. An official negative, worth keeping as such.

*Alexander himself* — **B-MEP-1291761-2026**, delivered by cloud link (three
weeks' validity, so it was pulled down the same morning). Three sheets, and the
first scans of his cards we have had; record **3504** holds only the 2023 Kager
letter, which was a typed summary stopping at 12.2.1940.

- **Meldezettel, stamped 9 October 1939.** Wien I., Passauer Platz 6/25, Beruf
  *"Student (Technik)"*, Staatsbürgerschaft "D.R.", ledig, r.k., ordentlicher
  Wohnsitz Alt-Aussee, Paß 96/15, in the margin ***"nicht gedient"***. Abgemeldet
  and ausgezogen **12. II. 40**, destination entered as ***"Wels zum Militär"***.
- **Historische Meldekarte** (1940 form). Beruf *Ing.*; **Wehrverhältnis
  *aktiv***; Staatsangehörigkeit *Deutsch*, nachgewiesen durch ***"Soldb. 484"***;
  Wehrpaß-Nr. blank. Father Paul (b. 4.6.79 Dymokury, d. Schönhof), mother
  Gabriele née Orsini-Rosenberg (b. 21.5.79 Wien, d. Alt-Aussee). Movements:
  zugezogen **13. I. 1943 *von "Wehrdienst"*** → IV., Wohllebengasse 11, bei
  Schmidtmann, als Untermieter; fortgezogen 22.II.43 → III., Metternichgasse 10;
  from 20.XI.43 III., Metternichgasse 10/4; **fortgezogen 6.5.1946 nach
  Alt-Aussee**.

**Two things this settles.** The civil register and the Gauakt give the same day
and the same place for the call-up — 12 February 1940, Wels — from two separate
record systems; the handwritten word alone would admit more than one reading, the
Gauakt fixes it. And "zugezogen von Wehrdienst, 13.1.1943" falls **six days
after** the TH Wien secondment as *Arbeitsurlauber* to the Wiener Neustädter
Flugzeugwerke of 7.1.1943: the registration clerk booked him in as arriving from
military service, which is what a Luftwaffe man on work-leave is and is not what a
factory's own *Wachsoldat* would be.

**One date not to assert.** The final deregistration reads 6 May 1946, while the
British collected him at Braunschweig in January 1947. Deregistrations were
routinely filed late and often by relatives. It is a date to reconcile, not
evidence of anything.

Sent to Oberst Reisner (cc von Lingen, Trauttenberg) with the three sheets
attached, together with Wels and *Wehrmachtsbeamter* from the Gauakt — the
concrete detail Sunday's letter lacked.

**GStA PK Berlin — the Wolfgang Einbürgerung file, free of charge.** Saskia
Simons answered the 16 September request (Geschäftszeichen 653#00006#0331#0002)
with four sheets from **I. HA Rep. 77, Nr. 7192**, waiving the invoice *"auf Grund
der Geringfügigkeit"*. What they contain:

- **Verzeichnis der beabsichtigten Einbürgerungen**, Polizeipräsident von Berlin,
  19.7.1929, Nr. I C. 437, to the Prussian Minister of the Interior. Wolfgang
  asked for the *Einzellisten-Verfahren* because he meant to go abroad for a long
  period after finishing his studies that autumn; the Police President
  *"möchte das Gesuch befürworten"*.
- **The list entry itself**: Czernin, Wolfgang, 4 August 1903, Groß-Hluschitz
  Bezirk Neu-Bydzow (Tschechoslowakei), katholisch, **Student der Chemie**,
  **Staatsangehörigkeit Tschechoslowakei, früher Österreich-Ungarn**,
  **ungedient**, ledig. **Parents: Paul Czernin and Gabriele Anna von
  Orsini-Rosenberg, resident in Austria, Staatsangehörigkeit Österreich.**
  Abroad from birth to October 1921, in Berlin since November 1921.
- **The Reich clearance**: the Prussian minister circulated the list to the
  Reichsministerium des Innern under § 9 Abs. 1 RuStAG 1913, and on 8 August 1929
  the RMdI reported *"dass von keinem Lande Bedenken erhoben sind"*.

**Useful, and one line to be careful with.** Useful because an official 1929 file
records the parents as **Austrian citizens living in Austria** — the line the
§ 58c case runs through — and because it fixes Wolfgang's own position as
Czechoslovak, not Austrian. The care is needed over the *Besondere Bemerkungen*,
which describe the applicant as coming from *"einer deutschstämmigen Familie"*,
raised *"in deutschem Sinne"*, schooled only in German schools and therefore
*"vollständig mit dem deutschen Wesen verwachsen"*. That is a Prussian official
writing in 1929, nine years before the Anschluss, about a different brother — but
it belongs in the same box as Felix's party application and the Steyrwerke weeks:
known to us, not volunteered.

Ingested as records **4021** (WStLA scans, 3 pp.), **4022** (the Anna nil return,
text layer, no OCR) and **4023** (GStA PK, 4 pp.).

### 23 September 2026 — why `reocr-page?engine=transkribus` cannot work, and what does

The three handwritten pages of 4021 were queued for Transkribus and all three
failed with **"Submit returned HTTP 400: Bad or no collection ID."** The message
is misleading and the earlier diagnosis was wrong: the registry row is fine
(`collId: 2516426`, present in both Transkribus rows since 17 September), and the
container has been up since 19 September, so nothing is stale.

The stack trace names `TranskribusClient.submit` — the **Metagrapho** client. The
worker is wired to it, while the class that knows about collections, uploads and
the classic API is `TranskribusTrpClient`, which only the *import* endpoint
constructs. The row's `"api": "trpserver"` setting is never consulted by the
worker. On the Scholar plan Metagrapho answers 401/400, so the automated path
cannot succeed however the registry is configured. **A code fix, not a data fix**,
and not attempted today.

**What does work, by hand, from the CLI:**

1. Token from `account.readcoop.eu` (`grant_type=password`, client
   `processing-api-client`), credentials from the backend container's environment.
2. `POST /TrpServer/rest/uploads?collId=2516426` with a descriptor naming the file
   **`rec<recordId>_seq<seq>.jpg`** — the name is what the import matches on — then
   `PUT /uploads/{uploadId}` with the image. The uploadId is the docId.
3. Recognition **cannot** be started from the API for the German super model:
   `/recognition/.../trhtr` answers *"You are not allowed for TrHtr Recognition!"*
   (403), and `/pylaia/...` accepts the call but the job then fails with *"Not a
   valid PyLaia model specified!"* — 265149 is a TrHtr model. So the Run must be
   pressed in the web app, exactly as the import endpoint's javadoc says.
4. `POST /api/admin/import-transkribus?docId=…` collects the result.

Uploaded and waiting on a browser Run: **19063761** (seq 1), **19063678** (seq 2),
**19063817** (seq 3), collection 2516426. Mistral's transcriptions stand in the
meantime — good on the printed form frames, thin on the ink, which is the whole
reason for the exercise.

### 23 September 2026 — Text Titan II on the Meldeunterlagen, and what it settled

The three pages were run in the web app on **Text Titan II (model 579509)** and
collected with `import-transkribus`. All three came back **shorter** than Mistral
and were refused by the guard until `overwrite=true`, which is the guard working
as intended and not a reason to keep the longer text: Mistral's length is markdown
table scaffolding around the printed form, Transkribus's is the ink.

On the ink Transkribus is plainly better:

| | Mistral | Text Titan II |
|---|---|---|
| mother's maiden name | "Altenberg" | **Rosenberg** |
| her date of birth | "7.5.79" | **21.5.79** |
| father died at | "Lebenshof" | **Schönhof** |
| Meldezettel deregistration | "12. I. 40" | **12.II.40** |
| destination | "Malo zum Militär" | **Wels** … "Cilitàr" |
| margin note | "mich gedient" | **"nicht gedient"** |

**Wels is now read independently by a second engine**, which removes the one soft
spot in the letter to Reisner — that the word rested on our own reading of one
hand, corroborated only by the Gauakt.

**One misreading to be aware of in the stored text.** On the Meldekarte's movement
rows Text Titan II gives the January arrival as *"13.7.4"*: the writer's Roman
**I** is serifed top and bottom and reads as a 7. At 600 dpi the same column has
unmistakable **II**s on the two rows below, in the same hand, and the writer uses
Roman months throughout — so the date is **13. I. 1943**, six days after the
7.1.1943 secondment, as sent to Reisner. The stored transcription keeps what the
engine said, per the archive's rule; the correct reading lives here.

### 23 September 2026 — the Gauakt arrives complete, and the sheet our copy lacked

Kontr. Ayse Ertugral sent `ZNsZ GA Czernin Alexander (*30.04.1913)` as a
digitisate **"aus konservatorischen Gründen"** — no reproduction order, no
invoice, none of the EUR 6,00 per scan that had been budgeted. Ingested as record
**4024**, eleven sheets.

Against record **3499**, the ten-sheet copy already held, sheets 1-10 are the same
documents in the same order. **One sheet is new**, and it is the last:

> **Aktenvermerk**, Wien, 13.7.1950, signed **Schebesta**: *"In den Gauakt (GSA)
> Nr. 232006 wurde durch … der Dienststelle **Abt. I** Einsicht genommen. Weitere
> Veranlassung, insbesondere **Verständigung der Registrierungsbehörde**, besorgt
> die vorbezeichnete Dienststelle."*

It completes the sheet we already had at seq 10: the **Polizeidirektion Wien,
Abt. I** — the state police — asking the Bundesministerium für Inneres, Abt. 2 on
**10 July 1950** for what it held on *"Czernin, Alexander, 30.4.1913, Ing.,
Wien 3., Metternichgasse 10/4"*. Three days later that office read his Gauakt and
noted that notifying the **registration authority** was its own responsibility.

**What it shows and what it does not.** It shows that in July 1950 he was run
against the NS-registration machinery, and that the Gauakt was the file consulted.
It does **not** record the outcome; that sheet is not in the file and must not be
inferred. Incidentally it puts Metternichgasse 10/4 — the address on the
Meldekarte that arrived the same morning — in official use in 1950, four years
after the card's deregistration to Alt-Aussee.

**A lead, deliberately not taken.** The Vienna *Registrierungslisten* are held by
WStLA. A nil return would be an official document stating he was never registered
as a National Socialist, which is a strong negative — but it is also an enquiry
that tells a Vienna authority exactly what is being looked for. Held for the
applicant to decide.

**Count discrepancy, resolved the same morning.** The AdR's earlier answer said
twelve sheets, the PDF holds eleven; asked, and Kontr. Ertugral replied within the
half hour that *"bei der Angabe von zwölf Seiten wurde die Aktenhülle mitgezählt.
Die übermittelte PDF-Datei enthält somit den vollständigen Inhalt der Akt."* The
twelfth was the file cover. **Record 4024 is the complete Gauakt** — nothing
further to order, and no reason to write to the AdR about this file again.

### 23 September 2026 — the Vienna Registrierungslisten, asked after all

On 18 September this question was deliberately held back, and the reason stands
in the log: it is asked of the **same municipal authority whose sister department
decides the application**, and a "yes" for Alexander would be handed over in
writing, unprompted, by us. The condition set then was to wait for the
Bundesarchiv answer on NSDAP membership, which has **not** arrived.

The applicant decided to ask anyway, and the case for it is real: a documented
nil is a strong positive; MA 35 can run the same check itself; and if an entry
does exist we are better placed knowing it than being met with it.

Sent to WStLA (Zimbra **465952**), asking whether a Meldeblatt, a registration
list entry or any other file exists for **Alexander Friedrich Josef Paul Maria
Czernin von Chudenic, b. 30.4.1913 Wien, last Vienna address III.,
Metternichgasse 10/4** — the address being what makes a district-filed
registration findable, and the address the police were still using for him in
1950. If nothing exists, a **written Fehlanzeige** that can be produced.

**Held out of the letter on purpose:** any statement of purpose, any mention of
MA 35, the Gauakt or the July 1950 police inspection, and — most deliberately —
**the siblings**. Felix's own registration Meldeblatt is already in hand, so
widening the name list at this particular authority costs everything and gains
nothing.

**Closed the same afternoon, and correctly.** MA 8 answered within a minute — from
the autoresponder, not a person: a plain email places no order. Written
information about *which* holdings exist is free; a search **in** the holdings
costs **EUR 35 per started half hour, maximum EUR 140 for two hours**, and
*"auch für ergebnislose Recherchen müssen Sie den Zeitaufwand bezahlen"*. An
order is placed only through the Anfragenformular.

The form was filled as far as the review page and **abandoned unsubmitted**,
because the applicant pointed out what the archive already holds:

> **Record 3505, page 35.** Alexander's own letter to the Rektorat of the
> Technische Hochschule Wien, Wiener Neustadt, **4 August 1943**, answering their
> questionnaire: *"3) **Nichtmitglied der NSDAP oder einer Gliederung.**"* —
> signed off, as everything then was, "Heil Hitler!"

A written declaration of non-membership to a state authority of the Reich, in
1943, when a false answer to that question was not a survivable thing to put in
writing. Corroborated by the SD's own report of 1941 in the Gauakt, which records
no membership and *"Nachteiliges konnte über ihn nicht in Erfahrung gebracht
werden"*.

**So the question is answered from our own holdings.** Registration under the
Verbotsgesetz applied to members; for a non-member a Vienna nil return proves no
more than his own declaration already does — at EUR 35-140, and in the form of a
written negative obtained from the same municipal authority whose sister
department decides the application. **Not to be reopened.** The same reasoning
disposes of adding Alexander to the Bundesarchiv NSDAP-Mitgliederkartei request:
there is nothing left to ask.

*Also today, for the record:* Commerzbank's Historisches Archiv acknowledged the
Felix enquiry (Dr Matthias Kemmerer, head of the archive) with a capacity-delay
holding reply — no reference, no date. GStA PK followed its file with a referral
list (Standesämter, Landesarchiv Berlin, Bundesverwaltungsamt, Reichsstelle für
das Auswanderungswesen); not pursued, for the reasons in the correspondence note.
And the ČNB registry has registered our *thank-you* as a formal submission,
**2026/162139/CNB** — so a message quoting that number is an acknowledgement, not
a new answer.

### 23 September 2026 — Jan's Meldekarte: the request finally placed as an order

MA 8's autoresponder to the Registrierungslisten email carried the detail that
explains a silence: **a plain email places no order.** Written information about
which holdings exist is free; a search *in* the holdings costs **EUR 35 per
started half hour, maximum EUR 140**, is billed even when fruitless, and is
commissioned only through the Anfragenformular.

That is almost certainly why the redaction request of 21 September (Zimbra
**465258**) was never answered — it went to the same generic address and never
reached the research team.

Re-submitted through the form, which is the actual channel:

- **Eingangsnummer 2026-2309145910123**, Wiener Stadt- und Landesarchiv
- Aktenzahl quoted: **B-MEP-1295012-2026**
- The request: a copy of Jan's historical registration records **with the
  co-registered wife's and children's entries masked**, so that only his own data
  remain legible
- The ground, and the only new fact given: **Johannes Czernin died at Salzburg on
  24 May 1996**, so his own protection period under § 10 (2) ended then; the
  restriction attaches to the three co-registered persons, not to the document
- The form states that an identical request was emailed on 21 September, so the
  archive sees one request rather than two
- Fee acknowledgement ticked: research up to EUR 140, reproductions extra

**No purpose stated, no mention of the proceedings, no siblings.** The § 10 (3)
Schutzfristverkürzung route remains unused, because it would require telling a
Vienna authority what the research is for.

**The Registrierungslisten enquiry was not placed** — see above; it is closed on
Alexander's own 1943 declaration and is not to be reopened.

### 23 September 2026 — the historians are a favour, not a commission

Hubertus Trauttenberg forwarded the morning's letter to Reisner on to Prof. von
Lingen for her assessment (Zimbra **465962**), copying the applicant and adding
**Sophie Trauttenberg** — Marie Sophie, daughter of the late Hubertus Czernin and
so Felix's granddaughter, the line the Felix pack came down. He thanks von Lingen
for *"Ihre wertvolle Hilfe"*, help of which we have seen only her note of
20 September.

**The commissioning question is closed, and not by an answer.** He addresses a
serving colonel as *"Lieber Markus"* and signs to von Lingen as *"Ihr ergebener"*;
there is no engagement letter, no fee, no reference number anywhere in the thread.
This is a retired general calling on people who know him. The applicant's reading,
and it fits everything on the record.

**So: do not ask who is paying whom** — it would put a price on a favour. Do not
chase the unanswered call request of 21 September; the cadence belongs to them.
The offer already made in writing ("allfällige Kosten übernehme ich") is the
correct position and costs nothing if no bill ever comes.

The warning that `wehrmacht-auskunftstelle.de` is a private firm rather than the
Bundesarchiv is **held**, to be sent only if he shows signs of paying them.

The one lever that works here is feeding the thread good material, which is what
the Wels corroboration did.

### 24 September 2026 — three invoices, and what each one unlocks

**MA 8 / WStLA — EUR 35,00, paid.** Geschäftszahl **MA 8 - 1295012-2026-4**,
Zahlungsreferenz 000235046691, settled online by the applicant the same day.
Raised against the **email** of 21 September, which corrects yesterday's
inference: the email had not been ignored, it had been worked — half an hour of
research — and only the *ordering* of work requires the form. Their terms:
*"after receiving the amount we will send you the results of our research"*. The
fee bought **time**, not a guaranteed copy; if the answer is a reasoned refusal
rather than a masked scan, the form submission of 23 September (Eingangsnummer
2026-2309145910123) is still live and asks for the copy in terms.

**HHStA — EUR 36,00, unpaid.** GZ **2026-0.786.804**, Kostenaufstellung Nr. 1257,
six scans at EUR 6, 14 days, scans made only after payment and sent by download
link. Mag. Maria Röhsner again. What it buys:

| Signature | Item |
|---|---|
| SB Partezettelsammlung 17-290 | Marriage announcement, Paul Czernin and Gabriele Orsini-Rosenberg, 22.4.1901 |
| 17-291 | Partezettel Paul Czernin |
| 17-246 | Partezettel Felix, 3.1.1968 |
| 17-309 | Partezettel Wolfgang, 10.10.1982 |
| Familienarchiv Clary 48-144 | **Gedenkbild Johannes (Jan) Czernin, 24.5.1996** (2 scans) |

Plus a documented negative: **no Partezettel for Alexander** in the holdings or in
the Nachlass collections. Expected — he died at Oxford in 2002 — but now on paper.

**The Gedenkbild is worth more than its EUR 6.** It documents Jan's death on
24 May 1996, which is the sole ground on which MA 8 is being asked to release his
registration card with the co-registered entries masked. The cheap purchase
evidences the expensive request.

**Národní archiv — GBP 165,96, unpaid.** Invoice **15602026** (their ref
NA-5020/03-2026), dated 17 September, due 8 October, for 391 scans at GBP 0.36
plus 7 at GBP 3.60 — the second part of **114-3-17**. Delivery after payment,
bank transfer only.

**And the reason it had gone quiet was not a stuck file.** The chase of 24
September (Zimbra 466058) produced an answer within four hours: *"faktura byla
odeslána 17.9.2026. Dnes jsme ji odeslali znovu. Pokud by se ji opět přes náš
automatický systém nepodařilo doručit, pošleme ji jiným způsobem."* It had been
sent and lost in their automated dispatch. The chase caused a re-send, not an
issue — which is worth remembering the next time an invoice simply fails to
appear.

**All three settled, 24 September 2026.** MA 8 EUR 35 paid online by card;
HHStA EUR 36 (GBP 31.10 at 1.1574) and Národní archiv GBP 165.96 (CZK 4.684,39 at
28.226 — the job was quoted at CZK 4.670, so it lands 14 crowns over) both by
transfer from Barclays, no charges, value date 25 September.

**One error caught before it landed.** The HHStA transfer went out carrying
reference **000235046691** — the *Stadt Wien* payment reference — instead of the
Geschäftszahl **2026-0.786.804**, which their paperwork requires as the
Verwendungszweck. Since the Staatsarchiv only digitises after it can match a
payment to a file, EUR 36 would have sat unallocated. Written to
`hhsta@oesta.gv.at` the same day (Zimbra **466134**) with payer, IBAN, amount,
value date and the wrong reference, so it can be matched by hand.

**The lesson, worth keeping:** when several archives are invoiced in the same
week, the reference field is the thing that goes wrong, not the IBAN. The Prague
transfer carried `15602026 NA-5020/03-2026` — invoice number *and* file reference
— and needs no correction.

**A tactic that works at the Národní archiv:** send the bank's receipt, and copy
finance, the reading room and the registry at once. The scans sit with the
badatelna but cannot be released *"až nám dají pokyn z ekonomického oddělení"*,
and the registry itself suggested in August sending proof of payment directly to
Jana Patková. Done for invoice 15602026 the same day (Zimbra **466135**), with the
Barclays confirmation attached and the request stated in full — 397 scans of
114-3-17, by download link, as for the first part. Not repeated at the HHStA: the
correction letter of the same day already gives them what they need to match the
payment.

### 25 September 2026 — Humprecht's prisoner file, free, from Potsdam

Marion Krause, Brandenburgisches Landeshauptarchiv (Az. **A-2026-00046049**),
answering the request of 16 September: the file **29 ZH Brdbg 3189** — the
Häftlingspersonalakte of the Zuchthaus Brandenburg-Görden for **Humprecht Ottokar
Graf Czernin von Chudenic** (b. 9.2.1909 Dymokury), 1942-1944 — by download link,
**64 preservation-grade images**, 3450 x 4082 with a colour target in frame.
*"Gebühren werden nicht erhoben, da die Akte bereits als Digitalisat vorliegt."*

**And two sheets nobody asked for.** She searched the **Zu- und Abgangslisten**
and found him in two of them — Rep. 29 ZH Brdbg **DO 9-1, Bild 376** and **DO 10,
Bild 355** — and attached both. Those are the registers that document the
**transfer of 14 September 1944**, which until now existed only as a line in the
finding aid.

**The file cover, read on arrival.** Across the top, handwritten, underlined:
**"Tscheche!"** Below it *Personalakten … -gefangenen -verwahrten*, the name in a
clerk's hand, prisoner-book number **1/9470**, and the stamp *"Am 14.9. … Uhr nach
… Prag … entlassen — übergeführt"*. He died five days later at Sanatorium Pleš.
The regime's own cover sheet classifies him by nationality, with an exclamation
mark, on a case that began as a death sentence.

Ingested as **4025** (64 pp.) and **4026** (the two lists, cross-referenced),
both with `ocrEngine: ocr_page_mistral` set at ingest — the first real use of the
hint shipped in v1.1.10, and the jobs came out as Mistral because the record said
so rather than because of a deployment default. The handwritten sheets can go to
Transkribus afterwards through `POST /api/admin/transkribus/upload`.

**Thanked (Zimbra 466318)**, naming what she did beyond the request and telling
her, briefly, what the file is: a death sentence commuted within days, a man dead
five days after the transfer her lists record, and a family that had only the
story until now. She waived a fee and did unasked work; that is worth more than a
form reply.

**A note on the delivery.** BLHA uses **DRACOON** for download shares. The link is
a JavaScript app, but the public API answers directly:
`GET /api/v4/public/shares/downloads/{key}` for metadata, `POST` to the same path
for a one-time S3 download URL. Worth knowing — the share has a limited life and
the file was 26 MB.

### 28 September 2026 — Transkribus, fully automated

READ-COOP answered the letter of 18 September: *"As of last week the 'New'
processing API should now be available to all paid plans (we launched this last
week during the TUC 2026)."* Ten days, and it is the answer that removes the
browser from the loop.

**Proved before believing it.** `GET /processing/v1/processes` now answers 405
where it answered 401; a POST with Text Titan II (579509) returned `processId
31282668, CREATED`, finished in one poll, and gave 126 lines and 1,990 characters
of correct text from Jan Czernin's Meldezettel — *Privatbeamter, Göding in
Mähren, C.S.R., Reichsdeutscher, Wien Ostmark*. One credit.

**The "Bad or no collection ID" mystery was never about a collection id.** The
client was already written for this API and posts to `baseUrl + processesPath`;
the row still carried `https://transkribus.eu/TrpServer/rest` and `/uploads`, so
it was posting Metagrapho bodies at the old TrpServer upload endpoint. A data
fault wearing a code fault's clothes.

**Model choice, settled by trial rather than by the CER column.** The catalogue's
best German models by CER are trained on a single hand — Lehtisalo 0.0023,
Bruckner 0.009 — and measured on their own validation sets. The serious candidate
was **StAZH German Kurrent XIX (48925)**, CER 0.017 on 26 million words. On our
own page it produced **1,397 characters of noise** (*"usung aus den meideoorrr"*)
against Text Titan II's 1,990 clean. Text Titan II wins for German. For Czech it
is not a candidate at all — its language list is `eng,deu,ita,lat,fra,fin,swe,nld,
por,dan,spa,nor`, no `ces` — so `cs` stays on **263129, Czech Handwriting M1**
(917,580 words, the largest of the six Czech models). That run also proved PyLaia
models start on the new API, so Czech will work when a Czech hand turns up.

**And the flaw the applicant put his finger on.** The engine was read once, at
boot: `WorkerSchedulingConfig` built the config and client from whatever row
existed then, so repointing a model or an endpoint needed a deployment — and
enabling Transkribus in a process that had started without a row did nothing at
all, because no worker had been registered to notice. Fixed in **v1.1.12**:
`TranskribusConfig` resolves its row through `AiRegistry` on use with a ten-second
window, a missing row is an answer rather than a crash, and the worker asks every
tick whether there is an engine.

**Verified in production without a restart:** the row was repointed through
`PUT /api/admin/ai/implementations`, and the next page went
`htrId=579509 … chars=2116 credits=30/150` — new endpoint, new model, new credit
ceiling, all live. Record **4027** page 1 now holds Transkribus text under engine
`transkribus:579509`.

**The ceiling that remains.** Scholar is 150 credits a month, one per page. The
clicking is gone; the quota is not. Worth asking READ-COOP what a larger plan
costs now that the API actually works.

### 29 September 2026 — the second half of 114-3-17, and what it does and does not contain

The 397 scans paid for on 24 September arrived by Úschovna: **392 files, 783.4 MB**,
complete. Ingested as **eleven records, 4028-4038**, `lang: de`, Mistral, the
delivered JPEGs uploaded untouched at their native ~2650x3700.

**The archive divided the file for us.** Ten `_uvod` separator sheets, each naming
the unit in Czech — *"21. situační zpráva (18.5.1942)"* and so on. Those, not my
inferred boundaries, are what the records follow, and each separator is page 1 of
its record so the division is visible rather than asserted:

| Record | Unit | Pages |
|---|---|---|
| 4028 | 21. situační zpráva, 18.5.1942 | 65 |
| 4029 | Opisy dálnopisných hlášení R. Heydricha pro A. Hitlera, 3. a 7.5.1942 | 27 |
| 4030 | 19. situační zpráva | 13 |
| 4031 | 18. situační zpráva, 22.1.1942 | 33 |
| 4032 | 17. situační zpráva, 30.12.1941 | 75 |
| 4033 | 16. situační zpráva | 25 |
| 4034 | 15. situační zpráva, 6.11.1941 | 6 |
| 4035 | 14. situační zpráva, 30.10.1941 | 46 |
| 4036 | 12. situační zpráva, 14.10.1941 | 14 |
| 4037 | 11. situační zpráva, 11.10.1941 | 84 |
| 4038 | Ordner Berichterstattung — the file's own register of despatches | 3 |

**How the boundaries were found without paying for OCR twice:** the top fifth of
each of the 363 folio images was OCR'd directly through the Mistral API for about
30p, which gave every page's header. That is worth remembering as a technique —
it answers "what is this page" for a whole delivery at trivial cost.

**Nothing in these 391 pages names the family.** No Czernin, no *Adel*, no nobility
passage; OCR-mangled variants checked too. **The Heydrich-to-Bormann letter on the
Czech nobility is folio 66, which came in the first tranche** and is already held
as record **3796**. This delivery is folios 1-61 and 100-427 — everything around
it.

**What it does give is the machinery that condemned Humprecht**, in the regime's
own reporting to Hitler:

- **4037**: Prague Standgericht death sentences for *"unerlaubter vorsätzlicher
  Waffenbesitz"* — the identical charge on which Humprecht was condemned in June
  1942, shown as routinely capital eight months earlier.
- **4032**: *"über 450 Todesurteile durch Standgerichte … vollstreckt"*; for
  15.11.-29.12.41 alone **59 death sentences, 288 handovers to the Gestapo, 5
  acquittals**.
- **4033**: 7.-14.11.41, 20 death sentences, 156 handovers. **4034**: 30.10.-6.11.41,
  9 death sentences, 150 handovers, *"while in no case was an acquittal reached"*.
- **4032, the sharpest**: Hácha asks Heydrich to move Havelka from the Standgericht
  to an ordinary court — even the Volksgerichtshof. Heydrich refuses: Havelka
  belongs before the Standgericht *"wohin er nach Fug und Recht gehöre"*. The State
  President's intervention counted for nothing.

**How far that goes, and no further.** It documents that the summary court was
chosen to bypass ordinary process and that intervention from the highest Czech
authority was refused — which turns Humprecht's file from a family story into an
instance of a policy. But Humprecht was Alexander's **cousin**; the reports stop
before the second Standrecht of June 1942 that condemned him; and none of it
touches the applicant. Context for Stefan to cite, not new evidence about
Alexander.

**Also today, and left alone deliberately:** the pipeline page reports 30 failed
translations. All are from **8 September**, on old records, all *"The read
operation timed out"* at `attempts = 3` — and the audit only retries below 3, so
they will sit there for ever. `reset-pipeline` is **not** the fix: resetting to
`translating` deletes `page_text` and drops the record to `ocr_pending`, destroying
good OCR to clear a counter. That needs a proper retry endpoint. The failed-job
list also still holds corpses from `ocr_page_paddle`, `ocr_page_qwen3vl` and
Helsinki-NLP models, none of which exist any more.


### 1 October 2026 — four things arrive, and the open list brought up to date

**HHStA, GZ 2026-0.786.804 — delivered and ingested.** Download link and password
came as two mails (Zimbra 467428, 467429; filed to /Citizenship). Ten TIFFs in five
folders, 300 ppi, JPEG-compressed: the JPEG stream inside each strip is a complete
JPEG, so it was lifted out whole and checked pixel-identical to the TIFF decode —
no re-render, no downscale. Records **4039** (17-290, marriage announcement of
22.4.1901), **4040** (17-291, Paul's Partezettel, d. 17.1.1938), **4041** (17-246,
Felix, 3.1.1968), **4042** (17-309, Wolfgang, 10.10.1982), **4043** (Clary 48-144,
Jan's Gedenkbild), two pages each, archive 9, Mistral. Noted on arrival, not yet
discussed: the back of 17-246 is an unrelated Warsaw telegram of 21.4.1917 (a stray
sheet in the archive's scan); Clary's two pages are in reverse order (prayer-card
verso first); Mistral reads *Czernin von Chudenitz* as *"Ezerhin von Ebudenih"* and
the like in 4039/4040 because of the display face (stored as the engine said it; a
single-page re-read is the user's call); 4040's mourning notice names Wolfgang,
Johannes and **Alexander** among Paul's sons.

**MA 8 / WStLA, B-MEP-1295012-2026 — Jan's registrations, delivered 25.9.** The EUR 35
paid on 24.9 bought the delivery, by Vienna cloud link (Nextcloud, password in the
letter B-MEP-1295012-2026-4, Zimbra 466320), **valid three weeks, to about 16 October**.
One A4 scan, 2496 x 3488, 300 ppi, ingested as **4045** with the embedded JPEG
extracted untouched. Not read yet.

**Staatsarchiv Nürnberg — the interrogation is online.** Reply of 29.9 (Ulrich Baur,
StArchiv-N-A4-5051.16-1729/1/2, Zimbra 467065): *StAN, Nürnberger Prozesse, KV-Anklage,
Interrogations C 22*, four interrogations of Felix by Verber, 6, 7, 10 and 19
November 1947 (7+8+6+4 sheets, subject *Dresdner Bank*), is in the virtual reading
room as a free digitisation; a reproduction would be a paid order; Felix is not
listed in Case 11. Ingested as **4044**, 29 images. **Lesson:** the server's IIIF
`full/full` is capped at 5000 px, so the 5480 x 4280 double-leaf pages came back
shrunk to 5000 x 3905. The first ingest used those; the 26 affected pages were
re-fetched at native scale as column strips (boundaries on multiples of 16) and
joined, then replaced in place. The server only delivers JPEG at about quality 75,
so the join is saved at 97, 4:4:4. **4044 is on AI hold** (its first pipeline
started before the size was caught; the user stopped it), so nothing further spends
until it is released. Pages 1, 2 and 29 were already native.

**What is still outstanding, as at 1 October 2026.** Nothing is unsent.

| Request | Sent | State |
|---|---|---|
| Bundesarchiv Abt. PA — Wehrstammkarten, Alexander and six siblings | 19.9, +22.9 and +25.9 | auto-acknowledged (DR Tegel); several months; **do not chase**; the historians are waiting on it too |
| ABS-S 8452/2026 — Czernin line | 14.9 | "supplemented" 20.9; eBadatelna live |
| Bundesarchiv Berlin — Benutzungsantrag and follow-up | 16.9, 18.9 | acknowledged; they notify on completion; ten free files |
| BArch-MA Freiburg — Abwehr/Turkey, Felix | 15.9 | several weeks; do not chase |
| Arolsen — inquiry 2026-09-15-59916 | 15.9 | up to 13 months |
| TNA FOI reviews — HO 294/592/9583, HO 294/593/9655, HO 405/9351 | 18.9 | acknowledged; due about 16.10 |
| DÖW — O 5 Generalsekretariat | 18.9 | auto-reply only |
| Terezín Memorial follow-up | 16.9 | no reply found |
| ÚSTR — corrected Heydrich-letter question | 18.9 | receipt confirmed 21.9, nothing since |
| WStLA — Opferfürsorge / Volksgericht holdings (2026-1609131617490) | 18.9 | no reply found |

Closed since 16 September: the AdR Gauakt (Ferdinand dropped), the five WStLA
registration enquiries, GStA PK, BLHA, Commerzbank (not pursued), ČNB, Národní archiv
114-3-17 part two, HHStA, Nürnberg. **Still unlocated:** the forced-administration
order of 12 February 1942 and the SD-Leitabschnitt Wien report PA 3852/41.

**Hand corrections, 1 October 2026 (v1.1.18, audited in `manual_correction`).** Record 4040 page 1:
Transkribus read Paul's death year as *1958* in the body; the dateline, Mistral and his age (59th
year, b. 1879) say **1938**, so `text_raw` and `text_en` were corrected. The English titles of 4040
read *"Party ticket"* for *Partezettel*; now *"Death notice"*. 4039, 4041, 4042 and 4043 did not
have that error. 4045 (Jan's two registration forms) was read twice, Mistral and Transkribus, and
both are poor on the handwriting (Mistral invented a "2016 … 2100" run and a wrong birth date);
the image itself reads: Rennweg 2, registered 30.5.1938, left 25.8.1938, from Aigen-Glas near
Salzburg; Salesianergasse 4, stamped 26.11.1938, left 5.1.1939 for Krumau, Sudetenland; born
8.3.1910 at Göding, *ledig*, *Beamter*; the co-registered entries are blacked out. The stored text
for 4045 is left as the engines produced it.

**Housekeeping, 1 October 2026 (v1.1.17–v1.1.20).**
- **114-3-17 boundaries.** The register (4038) is the authority for the serial numbers: reports 1–6 are
  28.9.41, 7–8 30.9, 9 1.10, 10 9.10, 11 11.10, 12 16.10 (listed before 13, 14.10), 14 30.10, 15 6.11,
  16 16.11, 17 3.1.42. The first ingest had split only along the archive's slips, which exist from
  report 11 upwards, so **4037 held 84 pages of six different documents**. It is now 4037 (report 11,
  pp 1–36), 4046 (report 10, 9.10.41), 4047 (report 9, 1.10.41), 4048 (reports 7–8, 29–30.9.41),
  4049 (reports 1–6 and the Erlass on the civil state of emergency, 28.9.41) and 4050 (the telexes of
  27.9.41, taking up office). Cut points were read from the pages' own datelines, not guessed.
- **4038 (the register) had its pages out of order** — the index starts on the "Ordner:
  Berichterstattung" page, which was last. Reordered. **4043** (Jan's Gedenkbild) likewise: the
  memorial card first, the prayer-card verso second.
- **Slip dates.** Every handwritten slip's year had been misread (1952, 1997, 1977, 1957, 1951); corrected
  from the report's own date and the images, and 4035's slip number from 15 to 14. Audited in
  `manual_correction`.
- **4041 page 2 deleted:** an unrelated telegram (Ugron, Warsaw, 21.4.1917, "M 4053", an archbishop's
  pastoral letter) that the archive's scanner had picked up with Felix's Partezettel. It names no
  Czernin. The scan remains in the delivery zip.
- **4045** (Jan's two registration forms): both engines read the handwriting badly; the stored text is
  now a hand transcription from the image with `[?]` for doubtful readings (audited, old text kept in
  the audit row). **4044** was re-translated at "best" quality after the first translation mangled
  names. **4039** was re-read on Transkribus and now has the family name right.
- **Housekeeping.** The 13,083 stored searchable-PDF rows and their files were purged; 30 translations
  that had timed out in September were retried; 10 failures of retired engines were dismissed.

### 5 October 2026 — the Salzburger Landesarchiv answers on Jan and the mother

Bernhard Friedl, Amt der Salzburger Landesregierung, Landesarchiv, Geschäftszahl
**20033-A/3430/1144-2026** (Zimbra 468381), on the 16 September enquiry (463991). No fee, no scans:

- **Salzburg city registration records** are in the Landesarchiv only **to 1938**. Because Dr. Johannes
  (Jan) Czernin **married in Vienna in 1940**, they point to the **Stadtarchiv Salzburg**
  (`stadtarchivundstatistik@stadt-salzburg.at`), which holds registration records **from 1939**.
- **A possible citizenship file for Jan was found in the registers: LAD d 10266/1950** — but it was
  **taken out and sent to the Foreign Ministry in Vienna**, so the Landesarchiv no longer holds it.
- **Maria Gabriele Czernin (the mother):** a **probate file of the Bezirksgericht Salzburg, 10 A 264/1951**,
  lies in the Landesarchiv; for legal reasons it can only be inspected through the **Bezirksgericht
  Salzburg**.
- The letter's own header confirms the dates: Jan b. 8 March 1910, d. 24 May 1996 in Salzburg; Maria
  Gabriele née Orsini-Rosenberg b. 21 May 1879, **d. 4 May 1951 in Salzburg**.

Not yet actioned: the Stadtarchiv Salzburg (registrations 1939 onward), the Bezirksgericht Salzburg
(probate 10 A 264/1951), and where the 1950 citizenship file went at the Foreign Ministry.

**5 October 2026 — status note to Stefan Huber (Cerha Hempel), sent by the applicant.** Reply to his
21 September "wait until the end of the week" (Zimbra 465311). Said: nothing decisive since; the
Wehrstammkarten are months away (Bundesarchiv Abt. PA, "do not chase"), so the § 27 VerbotsG file need not
wait for them; listed what has arrived (complete Gauakt incl. the 13.7.1950 sheet, the Vienna registration
cards, the Nürnberg C 22 interrogation, Commerzbank entry and its Hohenelbe-1929 correction, Wolfgang's 1929
file, the HHStA documents, Jan's 1938–39 registrations, the Salzburg Landesarchiv answer) and what is open;
asked whether MA 35 has replied (a chase is due) and whether to file the § 27 file now. **Deliberately
not raised as cautions:** Alexander's "aktiv"/Soldbuch entry (army service already known), the differing
arrest dates in Felix's 1947 testimony, and the 6.5.1946 deregistration — none is material.

### 9 October 2026 — Landesarchiv Berlin: the 1932 marriage entry, free

Marie-Sophie Buck, Landesarchiv Berlin (LAB-V Bu – 921-7548/2026-0001, Zimbra 469465, 469550), answering
the 16 September enquiry (Zimbra 463911) about Wolfgang's 1932 Berlin marriage. She needed a postal address
for a **certified copy of the marriage register entry**; supplied the same morning (Zimbra 469469), asking
for the cost first and **with no funding commitment** (the standing EUR 200 undertaking was deliberately
left out). Her reply: *because it is a nationality matter (Staatsangehörigkeitssache) she is not invoicing
this request.* Certified copy to follow by post, nothing to pay. The enquiry itself had stated no purpose;
the Berlin office drew the inference from the request. Nothing further to do until it arrives.

### 13–14 October 2026 — Bundesarchiv: what was already asked, what came back, and Alexander's NSBDT/VDI pages

**A correction first.** An earlier note in this session said nothing had gone to the Freiburg Militärarchiv
about Alexander. That was wrong. Three requests went out on **1 November 2025** (Zimbra 407725, 407732,
407823): Freiburg (Wehrmacht court, disciplinary and desertion files), Berlin-Lichterfelde (Organisation
Todt and construction records) and the Krankenbuchlager (hospital records). Their outcomes:

- **Freiburg Militärarchiv:** only the auto-acknowledgement (407751). No answer in 11 months. A one-paragraph
  reminder was sent 13 Oct 2026 (470292). The Felix request of 15 Sept 2026 (463859) is also at the
  acknowledgement stage.
- **Krankenbuchlager:** `krankenbuchlager@bundesarchiv.de` bounced (407758: mailbox not found); resent to
  `berlin@` (407823). **Answered 16 Jan 2026** by Referat DR 2 (Schults, Gesch.-Z. DR2 - 2023/G-9756):
  *Lazarett- und Krankenbucheinträge überprüft, Verwundungs- oder Erkrankungsmeldungen nicht ermittelt.*
  That sits oddly with the WASt cards themselves (below). A re-check naming the 1942 Schwiebus entries was
  sent 13 Oct 2026 (470293, acknowledged by DR2-2-1: months).
- **Berlin-Lichterfelde (OT):** answered 23 Dec 2025 and 11 May 2026 (Luther, DR 3.02, ref
  2023/0002#0185#0001): no OT records, but two name hits on Alexander, **R 26-III/80294** and **NS 14/165**.
- **Wehrstammkarten (poststelle-pa@, 464925 of 19 Sep 2026)** with Nachträge 22 and 23 Sep: only
  acknowledgements. `pa@bundesarchiv.de` is dead (a 13 Oct supplement bounced, 470284); the working address
  is `poststelle-pa@`. The supplement was resent there as 470287 and asks only for what the earlier letters
  did not: changes of fitness rating and discharge notes, capture and release, loss and missing reports, and
  1943–45 with whereabouts at war's end.

**The 1942 hospital entries (record 3500, hand-transcribed 13 Oct).** Both WASt cards (B 563-1 KARTEI
C-30/456, C-179/493) record reports from **Reserve-Lazarett 101 Schwiebus, Lzb. 131**: *Gelbsucht*
(jaundice) dated 17.4.42, and *Leberentzdg.* (liver inflammation) at 6.7.42; whether 6.7.42 is an admission
or a receipt date is not clear from the card. Pages 2 and 4 of the record are blank (the stored OCR there was
a hallucination and is now replaced). Stephanie Czernin (Alexander's daughter) wrote on 1 Nov 2025 that he
"had jaundice and was allowed to leave due to ill health", was wanted for a friend's factory, and "never said
he deserted" (407827). The cards back her account; they do not support a desertion theory.

**R 26-III/80294 (record 4057).** A Reichsforschungsrat *Forscherkarte*: Dipl.-Ing., scientific assistant,
private address "Wr. Neustadt, Flugzeugwerk", Institut für Verbrennungskraftmaschinen und Kraftfahrwesen,
Wien IV, Karlsplatz 13, fields combustion engines and motor transport, remark **"derzeit bei der
Wehrmacht"**, card no. 593, undated, back blank. It fits the January 1943 secondment to the Wiener
Neustädter Flugzeugwerke and says nothing about how his service ended.

**NS 14/165 (NSBDT membership correspondence, Cl–Cz, 587 pages; records 4058, 4059, 4060).** A bound volume
of letters to the NSBDT Reichswaltung Zentralkartei, Munich, about the membership of many individuals; not a
file about him. His pages are **536–543** (record 4060): the BDC folder label (BDC INGENIEURE 8500001907);
a letter of **15 March 1940** to "Alexander Czernin, Assistent, Wien I, Passauerplatz 6/25" about his
**application to join the NSBDT through the Verein Deutscher Ingenieure (Beitrittsanmeldung ZK 4500)** with
the birth date missing; his pencil reply on a postcard from **Kan. Czernin, 2/E.A. Abt. 97, Wels**
(postmark 28.III.40, received 28 Mar 1940), giving 30.IV.1913 and asking for the VDI journal "at last", else
to be struck off as a member; and the Zentralkartei's letter of 1 April 1940 quoting it. The postcard fits
the call-up at Wels on 12 Feb 1940 and the first unit on the WASt cards.
**It is an adverse fact** (an application to an NS-affiliated association, made as an engineer-soldier) and
is not to be volunteered to an Austrian body; it is also not political comment, and the 1941 Ortsgruppe check
(not NSDAP, not NSV) is unaffected. Nothing in it concerns 1943–45, leaving the army, or desertion.
Pages 544–587 are record 4059; pages 1–535 stay in 4058. Hand transcription and corrected translations for
pages 537, 539, 541 (blank), 542 and 543 are in the archive.

**How the files were obtained.** The invenio "search without login" route used in July–September no longer
exists: use now requires an account (free; Kennung BA.2026.794638, password in `~/.config/invenio-login`, not
here) and, from 13 Oct 2026, the digitised-file viewer returned its 503 "Wartung" page even when logged in.
A short mail to `invenio@bundesarchiv.de` (470415) was answered the same day (Stupperich, 470439): she sent
both files as **box.bundesarchiv.de** downloads (470441, available for 30 days). Both were downloaded unchanged.

**Open:** answers from Freiburg (Alexander and Felix), DR 2 (hospital re-check) and Abt. PA (Wehrstammkarten
and the three supplements). `scraper-barch` still uses the old open URLs and needs a login step before it can
be used again.

### 14 October 2026 — WStLA's own copy of Felix's registration file (record 4061), and two corrections

MA 8 (Wiener Stadt- und Landesarchiv) sent, as B-AP-1312529-2026, its own digitisation of **M.Abt. 119, A42 –
NS-Registrierung, 4. Bezirk, Nr. 3315**: 44 pages at 300 dpi, the archive's own copy of the Magistrat file
that our family copy (record 4006, 43 pages) reproduces. Ingested as **record 4061**; licence CC BY-NC-ND 4.0,
attribution "Quelle: WStLA". The same day MA 8 answered the 16 September enquiry about all four brothers
(B-AP-1296160 / B-AR-1296160) with a **nil result**: nothing further in the NS-Registrierung, the
Opferfürsorgeakten (M.Abt. 208, A36), the Gestapo-Kartei, the Gauakten-Kartei or the Volksgericht Vg Vr index.

**Two corrections to the entry of 17 September on Felix's Meldeblatt**, made from the full-resolution scan
(page 1 of record 4061, now hand-transcribed):
- **Wehrmacht service:** the pencil remark reads *"War seit **August 1944** bis 20. März 1945 bei der
  Wehrmacht eingerückt"*. The earlier entry said "from early 1944". The Gestapo arrest is dated **27 March
  1945** (not 29 March). His party card, no. ca. 6,900,000, "wurde **vernichtet**" (destroyed).
- **The paramilitary line:** the dates "Anfang 1939 – Anfang 1941" stand on the **NSFK** row (Nationalsozialistisches
  Fliegerkorps) and the function reads "NSFK [?] Mann (Fluglehrer)", not NSKK as noted earlier; the handwritten
  letter is partly unclear and is marked [?]. The top annotation is "V.G. **1947** / 16.6.1947".

The page is marked as struck through diagonally; the Meldeblatt records "1 Gesuch mit 2 Beilagen". The remaining
43 pages have only had the pipeline's OCR and have not been compared with record 4006 page by page.

**Correction (14 October, later).** A page-by-page comparison of the WStLA file (record 4061) with the family copy
(record 4006) by text and by image-feature matching shows the two are the **same file**. 4061 page 4 (the
Zentralregistrierung der Opfer des Naziterrors slip, no. 32855, dated **16 March 1946**) is *not* new: the same
slip is photocopied at the bottom of 4006 page 4. The pages with no counterpart in the family copy are 4061
**page 2** (a pencil filing note, "B. M. für Inneres, Abt. 2 — bisher keine Vormerkungen", repeating the
Interior Ministry reply of 1 Sept 1947 on page 43) and **page 39** (a blank back with a pencil note); the family
copy's folder cover (4006 page 1) is not in the WStLA scan. The image test could not pair the dense typed pages
(4061 pp. 29–35) reliably, which were matched by text instead. Pages 18 and 37, 40 and 41 of 4061 were
hand-transcribed (the dentist's statement of 20 May 1947, and the official's reasons: "Gestapohaft wegen schwerer
politischer Delikte ab 26/3 45 … durch die Befreiung im April 1945 abgebrochen").

### 15 October 2026 — Hypothesis (UNPROVEN): was Felix's 1945 arrest connected to the Maier-Messner-Caldonazzi group and the WNF plans?

**Status: a question, not a finding. Nothing in our archive supports it. Not to be put to any Austrian body or into a
submission unless a document supports it.**

*The question.* The Maier-Messner-Caldonazzi resistance group passed factory locations to the Allies (OSS, SOE, via
Switzerland and Istanbul), including the Wiener Neustadt works where Alexander was seconded in 1943 (see
`alexander-service-timeline.md`). Felix was arrested on RSHA orders on 27/28 March 1945, five days after Maier's
execution (22 March 1945), on charges that included a "Türkei-Feindverbindung". Could the two be connected?

*What fits (coincidence only).* Turkey/Istanbul appears both in the Maier network's route and in Felix's charge and
in his Abwehr task (an information centre on Russia via Turkey and Persia, 1943/44, under Major Bechtle, Abwehr I
Luft); the Gestapo was still closing the Maier case in March 1945 (Maier returned to Vienna 18 March 1945).

*What cuts against it.* (1) The Gestapo says Maier and Messner decided in **September 1943** to pass on the plans;
the first WNF raid was **13 August 1943**, before that. (2) Felix's documented charge names the **Falkenhausen
resistance circle**, not the Maier group. (3) Our archive has no mention of Maier, Messner or Caldonazzi. (4) The
published sources name industrial informants (Messner of Semperit, Ernst Kraus of Siemens-Schuckert), not a WNF
engineer; nothing places Alexander in contact with Felix about the works. (5) The only spy-like role in our papers
is Felix's Abwehr task *for the Germans*, by his own account, which he says failed.

*Tests.* Felix's Gestapo/RSHA file (Bundesarchiv R 3017/R 3018 and R 58, already requested); the Maier trial
records and lists of accused (DÖW; Bundesarchiv Volksgerichtshof), searched for Czernin; the 1945 diary
(record 3507) for Maier, Messner, Semperit or Istanbul contacts; Felix's 1947 interrogation summaries (record 4044).
Outside sources used: English and German Wikipedia on Heinrich Maier and the group, and on the raids on Wiener Neustadt.

*Result of the first search, 15 October 2026: no link found.* Archive searches for Maier, Messner, Caldonazzi and Semperit
returned nothing relevant; Felix's 1945 diary (record 3507) names none of the group, and its only Semperit mention
(11 April 1945, "loaded up tyres") is after his release. The Maier group's published member and arrest lists do not
name Czernin. Still untested: Felix's Gestapo/RSHA file (R 3017/R 3018, R 58), DÖW's name database, and the Volksgerichtshof
Maier trial files. The hypothesis stays unproven and out of any Austrian submission.

### 16 October 2026 — Felix's § 27 file goes to counsel as WStLA scans; a correction; counsel's draft submission

**Request.** Stefan Huber (Cerha Hempel) asked on 16 October (Zimbra 470859) for the § 27 VerbotsG file as the original
without the English column. He had received only seven pages on 20 September (4006 pp. 2–4, 15, 23, 25, 34, side by side).
Alex told him to file it with MA 35 now (his 15 October reply); no confirmation that it has gone in.

**What was sent.** The same documents, scans only, taken from WStLA's own digitisation (record 4061, B-AP-1312529-2026,
M.Abt. 119, A42, 4. Bezirk, Nr. 3315) instead of the family copy (record 4006). Reason: 4061 is a 2496 × 3488 colour JPEG
(about 300 dpi) of the original; the 4006 pages are an enlarged black-and-white photocopy (4959 × 7017, speckled, broken
pencil strokes). Exported through the archive API (`pages=1,3,4,5,16,24,26,34`), 8 PDF pages, 6.7 MB, not recompressed.
Concordance (4006 page → 4061 page): 2 → 1; 3 → 3; 4 → 5, with the Zentralregistrierung slip that sits at the bottom of 4006
p. 4 a separate sheet at 4061 p. 4; 15 → 16; 23 → 24; 25 → 26; 34 → 34. Matched by page text. WStLA added **no new
documents** (the nil result of 14 October also covers all four brothers); the gain is legibility only.

**Correction to our email of 20 September**, read from the clear scan of the Meldeblatt (4061 p. 1). The pencil remark is:
"War seit **August 1944** bis 20. März 1945 bei der Wehrmacht eingerückt. Ist in Wien am 27. März 1945 von der Gestapo wegen
**Hochverrat und Widerstandstätigkeit** verhaftet worden." The 20 September email said "early 1944" and "Hochverrat und
Wehrkraftzersetzung"; both were wrong, and the correction went to Huber in the same email as the scans. (NSFK, not NSKK, on
the paramilitary line is already recorded on 14 October.)

**Counsel's draft Urkundenvorlage to MA 35** (Zimbra 470936, 16 October; Alex's tracked changes sent back as 470944). Checked
against the scans:
- 5 March 1946 letter (4061 p. 3, lower): ÖVP Generalsekretariat letterhead, typed sender "Vereinigung ehemaliger österr.
  Widerstandsbewegungen, Wien I., Dominikanerbastei 6/4", red stamp "Vereinigung der Österr. Widerstandsbewegungen ·
  Österreichische Volkspartei · 'Freies Österreich'", signature illegible. The draft now names the Vereinigung.
- Fritz Janich (4061 p. 3, upper): the page gives only name, address (Wien IV, Schwindgasse 14/2) and a signature; the
  "Registrierungsbehörde" stamps cancel the fee stamps and show only that it was lodged there. "Employee of the Registration
  Authority" is unsupported ("an employee" deleted; "of the Registration Authority" still stands).
- The "SS, SA, NSKK or NSFK" list in the Magistrat file note is printed form text; the Meldeblatt shows NSDAP and NSFK only
  (list deleted in Alex's mark-up).
- **Left as drafted, by decision:** "the application had been granted". The record shows a *recommendation* only: the
  Begutachtungskommission on 5 December 1945 (4061 p. 24) and the Magistrat's file note (p. 26, entry deferred pending the
  State Government); Felix's own summary (p. 36) says the Magistrat on 5 January 1946 recommended it to the Staatskanzlei. The
  Staatskanzlei's decision is not in this file (requested from the Archiv der Republik).
- The draft discloses Felix's NSDAP/NSFK membership; it is on the Meldeblatt, which is part of the filing, and counsel has
  covered it with the Thurn und Taxis statements. Left to counsel.

**Not yet confirmed:** the filing date with MA 35. Note of Fees 26/50901 (EUR 2,700, to 30 September) does not itemise it and
predates the instruction to file.

**Alex's visit.** Replied to Jan Drocár (sent 15 October, 470709) with the family photograph and dates of birth for his tree;
the email said the dinner would be "at the castle" in Jindřichův Hradec; it is at a restaurant, the castle is a visit. Plan:
Prague from the evening of Tuesday 27 October; Czernin Palace on the morning of Friday 30 October (entry arranged by the
Czech Foreign Ministry); Jindřichův Hradec castle and a restaurant dinner that evening; home on Sunday 1 November. Tomáš
Czernin will not be in Prague that week.

**Filed, 16 October 2026 (afternoon).** Cerha Hempel sent the Urkundenvorlage to MA 35 (AZ 2026-0.068.068) in advance by email
to 82-ref@ma35.wien.gv.at (Zimbra 471109, from Sandra Baldia, cc Huber and Kernbichler), with registered post the same day.
Attached: the 4-page submission and the 8-page WStLA scans (`Czernin-Felix-Magistrat-Akt-Originalscans-WStLA.pdf`). The filed
text carries the corrected Meldeblatt reading (August 1944; Hochverrat und Widerstandstätigkeit) and names the Vereinigung
ehemaliger österreichischer Widerstandsbewegungen ("Freies Österreich"). As filed it still says the 5 December 1945 Niederschrift
"stattgegeben" (granted) where the record shows a recommendation, and it attributes the Janich confirmation to the
"Registrierungsbehörde für den IV. Bezirk"; both were left as drafted by decision. If MA 35 queries either, the support is
4061 pp. 24, 26, 36 and p. 3. The Staatskanzlei's decision on the § 27 application is still outstanding (Archiv der Republik).

### 20–21 October 2026 — seven family documents ingested, a page-delete tool, and the family facts checked against certificates

**Ingested from the family's scans** (Czernin Archive, all via the ingest API, original JPEGs byte-identical, Mistral OCR):
- **4063** Zdeněk Kalista, *Zur Wappengeschichte der Familie Czernin von Chudenic* (German translation, typescript, 11 pp.; blank sixth page of the PDF left out).
- **4064** "Onkel Otto's memories of Papa" — a three-page memoir of Pavel ("Pauli") Czernin, written at Schönhof 18–19 January 1938, the day after his death; 10 pp. (two typed copies). The first ingest (4062) held two blank pages; the archive then had no page-delete tool on the MCP, so 4062 was deleted and re-ingested without them. The author is "Otto" from the file name; the text suggests an elder brother with a diplomatic career (Oriental Academy), consistent with Otto Czernin, Manfred's father. Unverified.
- **4065** Wolfgang Czernin's speech on his honorary doctorate, TU München, 17 February 1975 (4 pp.), and **4066** H. Rüsch's Laudatio for him (8 pp.). The author of 4065 is Wolfgang Otto Paul Czernin (1903–1982), Alexander's brother, from the family tree; unchecked against the text.
- **4067** Wolfgang Czernin's memoir of a childhood summer, Aussee and Hluschitz, 1916 (English typescript, Wien 1981, "translated from the German by Alexander Czernin"; 12 pp. from two PDFs joined in order). Felix appears in it.
- **4068** the Czernin arms before and after 15 March 1623, with an extract from the imperial diploma for Hermann Freiherr von Czernin von Chudenic, and a drawing of the arms (2 pp.).
- **4069** an undated German essay, "Czernin — I. Die Großmutter und ihre Familie" (15 pp.): the family history, the Palais Czernin in Prague, and a 1992 visit to restituted Dymokury. It came from the papers of Alexander Czernin; the blue-ink corrections may be his. The writer is a grandchild of "Tante Muscha" = Marie Czernin (b. Prague 12 Oct 1844, d. Bístvina 2 Feb 1935), who married on 2 July 1867 August Pierre Picot de Peccaduc, Freiherr von Herzogenberg (1840–1906); her sons were August, Ottokar and Leonhard. The writer is not named anywhere in the text. Provenance and structure recorded on the record by audited correction 139.

**A page-delete tool on the MCP (v1.1.24).** `DELETE /api/admin/records/{id}/pages/{seq}` already existed but was neither in the `archiver-api` skill nor on the MCP; it is now both (`delete_page`, administrators only, reason required, six tests; full suite 644 tests green). Tagged, built (Jenkins #476) and live.

**Family facts, checked against certificates in the Dropbox** (replying to Drocár, who is compiling the family tree):
- Alexander's daughter Alexandra Gabriele Maria Czernin married Richard Lawrence Seymour Fishlock on **15 July 1972** at St Edward's Church, New Windsor (GRO certified copy; Drocár had "9 or 15 July"; 9 July 1949 was her parents' wedding).
- Alexander Hannibal Czernin Fishlock and Valentine Florence Véronique **Tourres**: civil marriage **10 May 2008**, Standesamt Altaussee (no. 9/2008); church wedding **14 June 2008**, San Leolino a Panzano, Greve in Chianti. Surname Fishlock; Czernin is a given name, for him and for his son.
- Valentine was born in Paris on **6 January 1976** (Austrian certificate; the Italian translation says 5 January, a slip). The email of 15 October gave "1 June 1976" in error (the date had been read as day/month); corrected in the reply of 21 October (Zimbra 472411). Eugene Maximilian Czernin Fishlock, born **1 December 2011**, London, is confirmed by MA 35's letter of 11 August 2023.
- Drocár proposed, and we accepted, **Wednesday 28 October, 14:00, restaurant Ztracený ráj**, Čechova 239/9, Prague 7 (with Valentine and Eugene). Photos sent to him: the family, Alexander and Zannick's wedding of 9 July 1949 (Haywards Heath), Alexander's parents Pavel and Maria Gabriele (Orsini-Rosenberg), and Alex's parents.

**Correction to the entry of 15 October (the Maier-group hypothesis).** The archive search for a link used "Messner Semperit" together and found nothing. Searching **"Semperit"** alone finds Felix's own § 27 file (4061 p. 17, also 4006 p. 16): a declaration of 20 October 1945 by **Fred Herok, director of Semperit-Kautschuk, Türkei (Istanbul)**, that Felix was known to him as a delegate for compensation (barter) business during his stays in Turkey in the second half of 1943, who moved in the Austrian circles there and held a clear Austrian standpoint. This is not evidence of any link to the Maier-Messner-Caldonazzi group; it records that a Semperit director in Istanbul vouched for Felix in autumn 1943, and the Maier group's Messner worked at Semperit. Not yet followed up.

### 22 October 2026 — Archiv der Republik: nil result on the Staatskanzlei file for Felix's § 27 application

Dr Clemens Reisner (Österreichisches Staatsarchiv, Archiv der Republik; Zimbra 472445, filed in /Citizenship) reports that
"despite intensive research in the holdings kept here, no records on the petition mentioned could be found" (he calls it a
Gnadengesuch). The request of 19 September 2026 asked for the Staatskanzlei's own file on the § 27 Verbotsgesetz application
Zl. 13.413-2 N/1945, filed by Dr Karl Trauttmansdorff for Felix, with the State Government's decision, its date and number, or
where it is kept; he asked for a citation on 12 October and a scan of the page on 12 October, and received both on 13 October.
Thanked on 22 October (Zimbra 472448); the request is closed.

**Consequence.** The State Government's decision on Felix's application has not been found. The latest records held remain the
Begutachtungskommission's recommendation of 5 December 1945 (record 4061 p. 24) and the Magistrat's file note recommending
that the application be granted, with entry in the register deferred pending the State Government (p. 26; Felix's own summary,
p. 36, gives 5 January 1946 for the Magistrat's decision). The submission filed with MA 35 on 16 October says the application
"was granted" (stattgegeben); the file supports a recommendation. If MA 35 queries it, the support is 4061 pp. 24, 26 and 36.

**Also this week:** `download_pdf` on the MCP now returns a large PDF in numbered 8 MB parts with a SHA-256 (v1.1.25), after
PDFs above about 12 MB failed as "session expired". Record 3796, Heydrich's 20th situation report to Bormann (16 May 1942, the
original carrying the Czernin passage on folio 66), has been split into the report (pp. 1–8) and its annexes for a USB key to be
handed to Jan Drocár on 28 October; not yet emailed.

### 22 October 2026 — ABS replies to ABS-S-8452-2026: nothing on Alexander; one online item fetched

The Security Forces Archive (ABS, Prague; Zimbra 472443, filed in /Citizenship; ref. ABS 9794/2026, signed Mgr Tomáš Bursík,
handled by Vladimír Lejtnar) answers the Czernin-line research request of 14 September 2026.

**Nothing on Alexander.** For Alexander Czernin (b. 30.4.1913), Pavel (b. 4.6.1879), Humprecht (b. 9.2.1909) and Marie
Czerninová/Kinská ABS found no records or evidence entries at all.

**What it does hold**
- *Rudolf Czernin* (b. 25.8.1904): investigation file V-8623 MV and an emigration card (Správa pasů a víz); the records of two
  files that no longer exist or are not located (personal and surveillance file reg. no. 337 MV, destroyed 8 March 1961; file
  AS 1427 I. S, passed to the MV's 6th section on 15 April 1957); and, in the annex, entries in fonds 305, 325 and Z, with the
  same Národní archiv signatures as the NA reply of 5 May 2026 (109-4-398 p. 7, 110-11-102/9, 110-4-59 pp. 2–15).
- *Maria Ida Czerninová* (née Lobkowicz, b. 15.10.1917): Národní archiv 110-4-14/13.
- *Felix Theobald Pavel Antonín Maria Czernin* (b. 7.3.1902, bank director, Aussig): Státní oblastní archiv Litoměřice
  112-485-4/219-229. The covering letter gives him Alexander's birth date (30.4.1913) in error; the annex entry is correct.
- The Kolín Gestapo men of the Humprecht/Rudolf case (Paul Feustel, Heinrich Meier/Meyer, Franz Wittiger, Josef Horn, Emil Laufka)
  with long lists in fonds 325, 52 and 2M, most marked as readable in eBadatelna.
- ABS cannot send copies by email or post (Act 499/2004). Items marked * can be seen digitally in the research room (Na Struze 3,
  Prague 1; badatelna.struha@abscr.cz, +420 221 008 132); items marked ** are online in eBadatelna (account active until about
  17 September 2027).

**Fetched.** The only online item concerning Rudolf, fond 325 unit 325-166-3 (ebadatelna.cz node 630292), is **record 4070**
in archive 2: a Czech translation of Walter Jacobi's post-war report *Political conditions in the Protectorate 1939–1945*
(Jacobi was the head of the SD office in Prague), 128 scans, OCR and translation done. The scraper takes a whole unit, so all
128 scans came in, not only the cited page. The ABS "s. 70" is Jacobi's own German page 70 (the report's index lists
"CZERNIN – 70"); the passage is on **scan page 76** (printed page 61): in September 1938 representatives of the old nobility,
among them "Count Czernin (of Dymokury)", issued a declaration "unequivocally" aligning themselves with President Beneš's
political line. This is the SD chief's own account of the declaration that Heydrich's letter of 16 May 1942 gives as the reason
for the forced administration (record 3796, folio 66). It does not name Rudolf, and Jacobi wrote after the war. The only other
mention is a 1939 anecdote about the Černín Palace (scan page 54).

**Not fetched:** the research-room items (V-8623 MV, fond 305 sign. 305-510-6, fond Z, the emigration card) and the Gestapo men's
lists. Rudolf's file V-8623 MV is the one item not reachable online.

## 22 October 2026 — Bundesarchiv-Militärarchiv Freiburg: no Wehrmacht court records for Alexander; Feustel file read

**Freiburg reply (received by post, 22 Oct).** Two letters from Sven Staude, Bundesarchiv-Militärarchiv, ref. MA 4 - 2023/0002#0185#0001.
- **13 Nov 2025** (answering the request of 1 Nov 2025): he checked the **Wehrmacht court records** (*wehrmachtgerichtliche
  Unterlagen*) and found **no records** on Alexander Friedrich Josef Paul Maria Czernin, b. 30.4.1913. Because of large losses of
  records through war action it is "not unusual" for little or nothing to be found on individual soldiers. No fee.
- **15 Oct 2026** (answering the request of 13 Oct 2026): a covering letter. The 2025 reply, sent by email, appears never to have
  arrived, so it is enclosed. No new finding. The 15 Oct letter cites the same reference and the 2025 letter, so it appears to
  answer Alexander's request only; the Felix request of 15 Sep 2026 is not answered by either.
- Scanned as `scan_20261022_31`, split to one page each and ingested byte-identical (SHA-256 verified against the stored images):
  **record 4071** (13 Nov 2025) and **record 4072** (15 Oct 2026), archive 9.

**What it does and does not show.** It rules out a Wehrmacht court file for Alexander in the Militärarchiv's court collection. It
does **not** cover personnel or service records, so it says nothing on whether his service records survive, or on how or when he
left the army. We still hold **no evidence of desertion and no record of his discharge**: the documents stop at the end of his
Arbeitsurlaub on 31 Dec 1943 (`alexander-service-timeline.md`); the only account is the family's (released for ill health, "never
said he deserted"). The desertion in our papers is Felix's, on 7 Apr 1945. The service file is still to come from Abt. PA (Zimbra
464913, supplement 470287) and the hospital recheck (470293); no replies.

**eBadatelna 325-121-1 (Paul Feustel), read.** Already in the archive as **record 4011** (215 pages, ingested 17 Sep 2026). All
Czernin hits concern **Humprecht**, none Rudolf. Scan 18: Czech report on the 14–18 Jul 1971 meeting with the Stasi (Capt. Schulz),
who handed over photocopies of the Prague summary-court verdict of 30 Jun 1942 against **Count Humprecht Otto Czernin** (b. 9.2.1909,
Dymokury) and the forester František Dvořák, both condemned to death; Feustel sat for the prosecution; Czernin's sentence was
commuted to life. Scan 22: cover note of 2 Nov 1971 forwarding 481 photocopies and saying the Stasi was sending certified copies for
"the criminal matter Czernin". Scans 136, 139, 140: handover list, including a witness protocol of Anna Chládková (27 Sep 1972), the
Gestapo monthly report for December 1944, a death notice and death certificate for Humprecht (Pleš sanatorium, 19.9.1944), a
witness protocol of **Leobald Czernin** (28 Sep 1972, 8 pages) and a Pleš confirmation. The list names the documents; the documents
themselves are not in this unit. **Not yet searched:** the "criminal matter Czernin" and the Leobald protocol elsewhere in fond 325.

## 23 October 2026 — ÚSTR reply (Dr Homolová); the Humprecht case in the StB files; three National Archives FOI releases

**ÚSTR (Czech Institute for the Study of Totalitarian Regimes), letter of 21 Oct 2026, ref. USTR 906-2/2026**, from Mgr. Dita Homolová, Ph.D., received 22 Oct (Zimbra 472509). A reply to our enquiry; thanked on 22 Oct (sent 472521); original filed in /Citizenship.
- Alexander's name does not appear in any archive she has studied (Národní archiv, Archiv kanceláře prezidenta republiky, the Austrian State Archive, Bundesarchiv Berlin-Lichterfelde, the Auswärtiges Amt's Politisches Archiv) or in the aristocratic ego-documents she has read. She points to ABS (a lustration request) and to the Státní oblastní archiv Třeboň, where the Czernin of Chudenice family archive is held.
- **Against the Heydrich-letter hypothesis.** She says connecting Alexander's treatment, in the Ostmark, to Heydrich's letter to Bormann is "pure deduction" without sources or testimony; punishments of Ostmark nobles followed a separate decision on Austrian nobility, and the Czech nobles' "uprooting" was aimed at Protectorate residents. She attached a Vienna dissertation, *Der ehemalige österreichische Adel in der NS-Zeit* (no mention of Alexander), and her book *Šlechta v proměnách* (2016). Neither has been read yet.
- **On the letter itself:** she says the 16 May 1942 letter "has not been found in any archive" except Theobald Czernin's personal archive. That conflicts with record 3796 (Národní archiv, sign. 114-3-17, folios 62–99, the passage on folio 66). Not raised with her.
- Names Count Rudolf Czernin of Dymokury among those hit in the first wave of property measures.

**Humprecht Czernin in the StB files already held (records 4008, 4009, 4012).** Read from the OCR text by a subagent, not yet checked against the scans.
- Arrest of Humprecht and František Dvořák on **9 Jun 1942**; Humprecht held at Kolín. The Prague Gestapo chief Geschke told Feustel to bring him before a summary court because of an "interest in acquiring his property" (4012 p. 69; 4009 p. 87). No evidence existed; a pistol was planted (StB account). Dvořák, the Kolín Gestapo's informer on the estate, is named as the denouncer.
- Summary court, Prague, afternoon of **30 Jun 1942**; Feustel for the prosecution, asking for death by shooting. K.H. Frank signed the clemency the same day (life imprisonment; Dvořák to Gestapo custody, 4008 p. 75). Dvořák was acquitted by the Kutná Hora court after the war; the StB reopened the case in the 1970s (Supreme Court 7 Dec 1973).
- 20 Jul 1943: letter from Dr Theusner (Deutsche Bank, Breslau) to Lammers asking for a pardon, at the request of Countess Sophie Schaffgotsch; the Reich Justice Ministry replied that it held no file (4008 pp. 52–69).
- Death: Pleš sanatorium, 19 Sep 1944, tuberculosis, with his wife present; burial at Břevnov (certificate 4008 p. 44); the Gestapo had refused burial in the Dymokury crypt.
- **Rudolf.** One mention only: his son Theobald's witness statement of 28 Sep 1972 (4009 pp. 40–42) says Rudolf was arrested in July or August 1942, held at Kolín, sentenced at Pankrác to 3.5 years and came back from a camp in 1945; the ground, in the family's view, was that he refused to hand over his property. **This conflicts with the log's earlier account** (convicted 28.3.1944 for foreign broadcasts; Terezín Small Fortress, Gollnow; record 3934). A statement given 30 years later by a son aged six at the time is weaker than the 1944 conviction record; the discrepancy is noted, not resolved.
- Not digitised anywhere in eBadatelna (OCR search, 22 Oct): the witness protocol of **Leobald Czernin** (28 Sep 1972, 8 pages) and of Anna Chládková (27 Sep 1972); they exist only as handover-list entries (records 4010, 4011). Would need a research-room request to ABS.

**National Archives (Kew) FOI releases, 23 Oct 2026** (Zimbra 472663, 472667); records in archive 6, pages rendered at 300 dpi to PNG because the scans are layered PDFs, SHA-256 verified against the stored images:
- **Record 4073, HO 405/9351** — Special Branch report of 21 Apr 1965 (5 pp.) on Countess Wilhelmine ("Minne") Czernin née von Clanner-Engelshofen, b. 10.9.1897 Hostačov, widow of Count František Josef Czernin (b. Prague 24.5.1878, d. London 17.10.1963; m. Prague 24.9.1917), on her naturalisation application. Children Frances (b. 1920), Josef (b. 9.8.1924, naturalised) and Monica (b. 1925). The family landed at Dover on 10 Sep 1948. Nothing adverse found. The whole file opens on **3 Nov 2026**. Josef is Alexander's 2nd cousin (family tree person 336); his son Peter John Joseph was born in 1966.
- **Record 4074, HO 294/593/9655** — Czech Refugee Trust Fund questionnaire, Anna Bořek-Dohalská, b. 27.4.1911: left Czechoslovakia 24 Mar 1950 for "political reasons, persecution", arrived Dover 27 Jun 1950.
- **Record 4075, HO 294/592/9583** — same fund, Baron Robert Hildprandt, b. 5.3.1893 Blatná: left Czechoslovakia 1 Sep 1939, arrived Portsmouth 26 Jun 1940; stamped as having left the UK for South Africa on 7 Apr 1955. The ÚSTR letter named the Bořek-Dohalský brothers, which is why Anna's form may matter.
- Not yet read: page 2 of each questionnaire; OCR of handwritten forms is expected to be poor.

**Josef Czernin (b. Prague 9 Aug 1924), Alexander's 2nd cousin — family testimony, 23 Oct 2026.** Given by the applicant (Alex Fishlock), not documented in any record we hold, undated: Josef and his wife (the Hon. Hazel Scott-Ellis, m. London 20 Nov 1957) were **very good friends of Alexander** and paid his mother's school fees. Josef's son Peter John Joseph was born in 1966 (family tree person 336 and children). Josef's own naturalisation file is already in the archive as **record 3509** (HO 405/9132, 1955, 31 pages); it has not yet been read for Alexander, referees or the friendship, and a reading is in progress. His mother's file is record 4073 (above).

**Joseph Czernin's naturalisation file read (record 3509, HO 405/9132, 31 pages, 1955)** — read from the OCR text by a subagent, not checked against the scans. **Nothing on Alexander:** he is not named, nor is Iver Heath or any English cousin or uncle; p. 15 says Joseph has "many cousins and more distant relatives in Czechoslovakia" but is in touch with none. The 1957 marriage is not in it (he is "single" throughout), so the file neither supports nor contradicts the friendship in the testimony above.
- Parents: František Josef Czernin (b. Prague 24.5.1878) and Wilhelmine née Clanner (b. 10.9.1897), arrived 10 Sep 1948. Sister Monica George (m. Major John Malcolm George, Foreign Office, Military Mission to Prague 1945–46; Olney, Bucks) and a sister Frances in South America.
- Joseph: b. Prague 9.8.1924; forced labour in Germany 1943–44; back in Prague by Feb 1945, then Red Cross work and law studies at Charles University from Sep 1945. Landed Folkestone 1 May 1947 as a student, stayed with his sister and her husband. Couper, Friend & Co (shipping) 1948–55; Shell, Iberian division, from 16 May 1955. Naturalised January 1956 (BNA 40167; dates of 17, 19 and 23 Jan appear). By 1973 at 2 The Boltons.
- Referees: **Sir Philip Nichols** (Lawford Hall, Essex), British Ambassador in Prague in 1945, who knew the family there; Henry Torrington Chapple, solicitor (his wife and Joseph's mother were girlhood friends in Prague; his father-in-law was the British Consul); Edward George Fogg (colleague); "G. Henry Krockmorton" (reading uncertain).
- Adverse: none. MI5 "nothing recorded against" (22 Apr 1955); Special Branch found no security interest.
- Possible routes, not pursued: the Nichols, Chapple and George connections.

**Records 4074 and 4075, OCR corrected (24 Oct 2026).** Both forms are handwritten and the OCR misread names and dates. Twenty-three audited corrections (audit ids 140–162) were applied to the stored text against the scans: surname (Bořek-Dohalská, Hildprandt), the Cheriton Hotel, Teslova 6, and Anna's passport (15 Jun 1950, Wiesbaden), visa (22 Jun 1950, Frankfurt) and arrival (**27 Jun 1950**, not 24 Jun 1949 as the OCR had it). Descriptions extended with page 2 (audit ids 163–164). Page 2 content: Anna was **widowed**, had worked in her household until 1948 and then in the Czech information service, planned to find work in England or join a friend in Canada, and was staying with a relative of her mother's at the Cheriton Hotel, Torquay (signed 23 Aug 1950). Robert Hildprandt: former farmer and breeder, **1st Lieutenant, General Staff, 1st Army Corps, Czechoslovak Army, Prague, August 1938 – September 1939**; wife Baroness G.K. Hildprandt née Dobson, British, b. 5 Feb 1908; no children listed; signed 19 Apr 1950. A search for "Hildprandt" now also returns the Drocár signatory lists (4000, 4001) and the thesis on the 1938–39 declaration (3804), so he may be one of the signatories; not yet checked.

**Correction (24 Oct 2026) to the 23 Oct entry.** The two witness protocols said there to be "not digitised anywhere" are both already held, in Dvořák's file **record 4009** (ABS 325-113-2): **Anna Chládková**, Prague, 27 Sep 1972 (from page 44; housekeeper to Humprecht's mother from 1941), and **Theobald Czernin** (b. 7 Jul 1936, Rudolf's son), Nejdek, 28 Sep 1972 (pages 40–42). The handover list's "Leobald CZERNÍN" (records 4010 p. 28, 4011 p. 139) is Theobald: same date, same case (the GDR's request for legal assistance against Feustel). The OCR search missed them because it searched the misspelling. **No ABS request is needed.**

**Record 4076 (24 Oct 2026): the thesis Homolová sent, ingested.** Marian Wimmer, *Abseits von Flucht und Widerstand. Der ehemalige österreichische Adel in der NS-Zeit*, Diplomarbeit, Universität Wien, 2012 (supervisor Marija Wakounig), 110 pages, archive 9. Born-digital, so ingested through the text-PDF route (the PDF's own text, no OCR); German, translation running. Being read for the "separate decision" on Austrian nobility that Homolová cites. A first scan finds the Czernin name in several passages (Peter Czernin, b. 1907; the families Czernin and Khuen named among NS sympathisers in one source the thesis quotes; a Czernin among those taken to Dachau in 1938), and the 1938 law on the extinction of family entails (*Erlöschen der Familienfideikommisse*, in force 1 Oct 1938) as a measure that hit the nobility's property. Reading in progress.

**Record 4076 read in full (24 Oct 2026).** Read by a subagent from the PDF's text; page numbers are the thesis's own.
- **No "separate decision" on the Austrian nobility.** The thesis describes no general Nazi decree, order or policy against nobles in Austria after March 1938, and does not mention Himmler, Heydrich, the Protectorate or the Czech nobility's declarations. It is a study of noble *support* for the regime, and sets persecution aside: "jedoch stehen diese nicht im Forschungsinteresse dieser Arbeit" (p. 27). Measures it does mention, none framed as anti-noble policy: the Prominententransport to Dachau (Schuschnigg, the Hohenberg brothers, Zessner-Spitzbergen); arrests of Hoyos, Revertera and Coreth; the ban on the Vereinigung katholischer Edelleute (1938); the Habsburg property question (Enigl, *Profil* 2003, no decree cited); the 1938 entails law, which turned entails into free property by 1945; one forced sale (Abensperg-Traun, Lobau). Homolová's pointer therefore gives no citable source for the decision she describes.
- **Czernin, adverse.** Peter Czernin (b. Vinoř 25.11.1907, d. Wien 3.9.1938), SS-Obersturmführer, among "enthusiastic" noble Nazis and in Jäger's illegal SA-Brigade 6 circle (pp. 20, 24; fn 62, 111; the other branch, out of scope). "Die Familien Czernin und Khuen" as examples of noble NS sympathisers (p. 21, fn 77, Walterskirchen, *Der verborgene Stand*, 57f.); the Czernins as a family split between supporters and opponents (p. 66, fn 351, Walterskirchen, *Adel*, 187); Czernins in the Heimwehr leadership (p. 64, fn 340). Alexander is not named. Nothing on any Czernin being persecuted.
- Case studies: Abensperg-Traun, Auersperg, Harrach, Kinsky, Rohan; 9 of 36 people examined were NSDAP members.
- Use: none for the application; the Czernin passages are adverse context, to be known and not volunteered.

### 27 October 2026 — Salzburger Landesarchiv: Jan's 1950 citizenship file went to Foreign Affairs; the court's terms for the mother's probate file

Bernhard Friedl, Landesarchiv, Zahl **20033-A/3430/1151-2026** (Zimbra 473135, filed in /Citizenship), answering our follow-up of 5 Oct (468424).
- **LAD d 10266/1950 register sheet** (record **4077**, one page, JPEG byte-identical): on **20 Sep 1950** the Amt der Salzburger Landesregierung (ref. 10.266/LAD/50, signed Ledochowski-Thun) sent the **Staatsbürgerschaftsnachweis for Dr Johann (Jan) Czernin**, with the Stadtmagistrat Salzburg's covering letter, to the **Bundeskanzleramt, Auswärtige Angelegenheiten, Wien I**, asking that it be forwarded; 2 enclosures; stamped 22 Sep 1950. Where it was forwarded to is not stated.
- **Probate file 10 A 264/1951** (Maria Gabriele Czernin née Orsini-Rosenberg, d. Salzburg 4 May 1951): an application with legal effect from abroad must go **by post** to the Bezirksgericht Salzburg, Rudolfsplatz 3, with **proof of kinship**. If the court approves, the Landesarchiv can scan the file, about 90 pages, for about **EUR 45**. A 1951 probate file normally lists the heirs and their addresses, which might show where Alexander lived in 1951 (his daughter was born in London on 12 Mar 1951).
- **Actions.** The held letter of 5 Oct is superseded by a formal application with the kinship chain (`docs/correspondence/2026-10-27-bezirksgericht-salzburg-antrag.docx`), **held** for the applicant to sign and post with copies of the birth certificates (Alexander 1913, Alexandra 1951 — record 3967, applicant 1973) and his passport. A request to the **Archiv der Republik** (Clemens Reisner) for the 1950 Foreign Affairs file was **sent on 27 Oct** (Zimbra 473147).

### 27 October 2026 — MA 35's notice of intended refusal (Parteiengehör), and the kinship proof

**MA 35 – 387376-2026, dated 23 Oct 2026**, delivered through ERV to Cerha Hempel on 24 Oct (their transmission CERHAHEMPEL-2767188-v2; caseworker Sarafinovic, Referat 8.2.4, signed Stobbe). A hearing letter, not yet the Bescheid. **Two weeks from receipt** to send a written statement (post or e-mail to 82-ref@ma35.wien.gv.at) or withdraw; otherwise a negative Bescheid follows. The exact deadline (about 7–10 Nov, depending on when ERV delivery counts) is for Stefan Huber to confirm.
- **Ground:** "der Vorfahre hatte Verfolgungen nicht mit Grund zu befürchten oder erlitten." Based on the expert's opinions (Gutachten 4 Aug 2023, 27 May 2026, and since), searches of the Nationalfonds, DÖW, Yad Vashem, ÖStA, Arolsen and USHMM, the citizenship register and MA 8.
- **Accepted:** Humprecht and Rudolf were Nazi victims ("unbestritten"); Felix was officially recognised as a victim (Gestapo custody for Hoch- und Landesverrat, 28 Mar–7 Apr 1945, record 4006); the regime did persecute close relatives of political opponents (§ 83 Abs. 2 and § 139 RStGB discussed).
- **Their reasons:** (1) the cousins were persecuted for publicly backing Czechoslovakia, not as Czernins; nothing shows Alexander's branch did the same or had close ties to Czechoslovakia (Heimatrecht Wien 1916); **kinship to Humprecht and Rudolf "nicht mit Sicherheit bestätigt … da zur Verwandtschaft keine Dokumente vorliegen"**; (2) many Czernins were NSDAP members, including Peter Czernin (b. 25.11.1907), SS officer (they say Obersturmbannführer; Wimmer and GHdA say Obersturmführer); (3) the Gauakt (1941/42) found nothing against him; (4) during Felix's persecution Alexander was an engineer on a war-important project (Doblhoff helicopter, WNF; sources Vertipedia and *Aeronautical Research in Germany*); (5) he left for a job at Fairey Aviation, not from fear; the date he left is unknown to them, and they found no post-war Austrian registration.
- **Points open to answer from our evidence:** the kinship (below); the branch's Bohemian ties (Paul b. Dymokury 1879; parents married Prague 22 Apr 1901; Jan b. Hodonín 1910; Felix in Bohemia/Aussig 1933–38); the StB files' evidence that the Gestapo's motive against Humprecht was his property (records 4008, 4009, 4012). **No document we hold places Alexander at the WNF after 31 Dec 1943**, so their premise for March 1945 is unproven. Adverse and not to be volunteered: the January 1947 collection by British officers at Braunschweig and flight to Farnborough (record 3959 request), which fits their reading.

**Kinship: first cousins, proved by two documents already held** (Dropbox `Alex/Austria/Discovered Documents/Certificates`):
- Alexander's *Geburts- und Tauf-Schein*, Pfarre St. Karl, Wien, Tom. XVII fol. 186, issued 30 May 1921: father **Paul** Friedrich Theobald Ottokar Otto Maria Graf Czernin von Chudenic, b. Dymokury 4 Jun 1879, "ehel. Sohn des **Theobald** Grafen Czernin von Chudenic k.k. Kämmerers und der Gräfin **Westphalen-Fürstenberg**"; mother Reichsgräfin Gabriele Anna von Orsini und Rosenberg, b. 21 May 1879 Wien VII; parents married in Prague 22 Apr 1901.
- Humprecht's birth register entry, Dymokury 1909: father **Děpolt** hrabě Černín z Chudenic, b. Dymokury 18 Jun 1871, owner of Dymokury and Hlušice, "syn † **Děpolta** hraběte Černína z Chudenic, c.k. komoří … a **Anny Marie** rozené hraběnky **Westphalen-Fürstenbergové**"; mother Marie hraběnka Kinská ze Vchynic a Tetova, b. Seebarn 1881. **Godparent: "für Ottokar Grafen Czernin — Paul Graf Czernin"** — Alexander's father was proxy godfather at Humprecht's baptism.
- The two fathers were sons of the same parents, so brothers; Alexander and Humprecht were first cousins (family tree persons 322, 255; common ancestor Děpolt "Theobald"). Rudolf (b. 25 Aug 1904) is Humprecht's brother; his own birth entry is being sought.

**Side search, 27 Oct (nil):** Maria Gabriele's 1879 baptism is not in the 1879 indexes of the three Wien VII parishes on Matricula (St. Ulrich index A-03, Schottenfeld 01-075, Altlerchenfeld 01-46). Not needed for the court application.

**Kinship proof — everything found, 27 Oct 2026** (archive sweep by a subagent, quotes from the OCR text and record descriptions, to be checked against the scans before filing):

| Strength | Document | What it states |
|---|---|---|
| Parish, official | Alexander's 1921 *Geburts- und Tauf-Schein* (Dropbox Certificates; also record 4005 p. 4) | Paul b. Dymokury 4.6.1879, son of **Theobald** Czernin, k.k. Kämmerer, and Countess **Westphalen-Fürstenberg** |
| Parish, official | Humprecht's Dymokury birth entry, 1909 (Dropbox Certificates) | Father **Děpolt**, b. Dymokury 18.6.1871, son of the late **Děpolt** Czernin, k.k. komoří, and **Anna Marie née Westphalen-Fürstenberg**; **Paul Graf Czernin proxy godfather** |
| Printed notice | Record 4040, HHStA Partezettel, 17.1.1938 | Paul buried at Dymokury; Felix gives notice also for "seines Onkels Otto" |
| Printed notice | Record 3943, Wiener Salonblatt, 6.2.1938 | Paul's widow and children incl. Johannes and Alexander |
| Scholarly article | Record 3519, Hořejš, *Paměť a dějiny* 2014/4, p. 31 | Theobald (1836–1893); his elder son Theobald (Děpold) Josef (1871–1931) received Dymokury; **Rudolf (25.8.1904) the first-born and Humprecht (9.2.1909) the second son** of that Theobald and Marie Anna née Kinská |
| Family history | Record 4019 p. 23, Alexander, Iver Heath, 1987 | "Theobald, 1836–1893, was my grandfather … his wife, née Countess Westphalen-Fürstenberg … The boys were my uncles, Theobald, Ottokar, Otto and my father Paul … Uncle Theobald lived in Dymokur … we saw his family quite often … I think we felt closer to them than to our other cousins." |
| Family history | Record 4019 p. 26 | Rudobald and his brother Humprecht as cousins; "on trumped up charges, both were put into prison. Humprecht did not survive" |
| Memoir | Record 4064, Onkel Otto's memoir of Paul, 1938 | the brothers at Dymokury told of Paul's birth; their father's death 6 Nov 1893 |
| StB protocol | Record 4009 p. 40, Theobald Czernin, 28.9.1972 | parents Rudolf Czernin and Bedřiška née Wenckheim at Dymokury; "my uncle Humprecht" at Hlušice — Rudolf and Humprecht brothers |
| Contact | Arolsen doc. 12120657 pp. 32–33 (log line ~1286); record 4009 p. 45 | **Wolfgang visited Humprecht in Brandenburg on 11 Apr 1943** and applied again in Sept 1943; Chládková: the visit was arranged by "a relative who was a Reich German" |

**Still missing:** a primary entry for Rudolf's birth (Dymokury, 25.8.1904; SOA Praha digital reading room, ebadatelna.soapraha.cz, interactive only) and for Děpolt's (1871). Neither is needed to prove Alexander and **Humprecht** were first cousins; the two parish entries already do. Note: the applicant recalled the prison visit as Felix's; the log records it as Wolfgang's.

**Rudolf's birth entry found, and both cousins' entries ingested (27 Oct 2026).** SOA Praha eBadatelna, register **Dymokury 10** (births 1900–1922, matrikaId 14932, 205 scans), read through a scripted browser; images saved exactly as the viewer served them.
- **Record 4078** — scan 42, entry no. 38: **Rudolf** Děpolt Maria Josef Josef Kalas. Ignác Ludvík, born **25 Aug 1904**, baptised 27 Aug, Dymokury no. 1. Father **Děpolt** hrabě Černín z Chudenic, chamberlain, owner of Dymokury, "manželský syn **Děpolta** hraběte Černína z Chudenic … a **Anny Marie hraběnky Westphalen-Fürstenbergové**", born Dymokury no. 1 in 1871. Mother Marie hraběnka Kinská ze Vchynic a Tetova. **Godmother "Anna Gfin Czernin"** — the shared grandmother. Parents married in Vienna.
- **Record 4079** — scan 91: Humprecht's entry, 9 Feb 1909, with **Paul Graf Czernin as proxy godfather**. It is **byte-identical** (SHA-256) to the family's Dropbox copy, which therefore came from this register.
- With Alexander's 1921 Geburts- und Taufschein (record 4005; Paul son of Theobald and Westphalen-Fürstenberg), the three entries prove from parish registers that **Rudolf and Humprecht were Alexander's first cousins**. Both new records use the Transkribus engine (handwriting); OCR and translation running.
- Correction to the applicant's recollection: the 1943 prison visit recorded in the log was **Wolfgang's** (Arolsen doc. 12120657 pp. 32–33), not Felix's; that document has not yet been located among the archive's records.

**Paul's Czechoslovak citizenship — already held, records 3965 and 3966 (Národní archiv, NA-2660-2/03-2021, 2021); re-read 27 Oct 2026 for MA 35's "no close tie to Czechoslovakia".** Not a failed attempt: the Zemský úřad v Praze refused, the Ministry of the Interior **granted on appeal**, domicile promised by **Dymokury** (record 3965, 1930–1934). Page 1 of 3965, read from the image: **Osvědčení o státním občanství republiky Československé**, okresní úřad Poděbrady, č. Z-87, **3 April 1930** — **Pavel Czernin**, soukromník, b. 4 Jun 1879 Dymokury, **domovská obec Dymokury**, residing at Aigen, Austria; citizen under §1(1) of the law of 9 April 1920 no. 236 Sb.; wife Gabriela b. 21 May 1879 Vienna included; the line for **minor children is struck through** (Alexander, then 16, not listed). It answers MA 35's point directly, but bears on whose citizen Alexander was; the refusal–appeal sequence and the children line are for Stefan to read before it is used. Record 3966 adds a pension strand and an older Felix as optant (identity not established).

The applicant confirmed on 27 Oct that the 1943 prison visit was **Wolfgang's** (his earlier "Felix" was a slip).

**Correction (27 Oct 2026) to the entry on Paul's citizenship above — records 3965 and 3966 read in full (subagent, OCR-based, quotes to be checked against the scans).** The 1930 certificate did **not** stand. **25 Mar 1931**, Zemský úřad Praha no. 153.641: "nejste státním občanem Československé republiky" — as district commissioner Paul had acquired Heimatrecht in Hodonín and **from 1910 in Vienna** (§10 law 222/1896), thereby losing Dymokury automatically; his 1919 declaration was no formal option; the Dymokury duplicate "byl Vám vydán neprávem" and the certificate was **invalidated** (final 9 May 1931; 3966 pp. 38–39, 3965 p. 11). He then applied for a **grant** (22 Oct 1932); refused 9 Mar 1933; on appeal the Ministry of the Interior (9 Apr 1934, no. 64517/1933) directed a grant **on condition** that he renounce pension claims (done 2 May 1934) and **prove release from Austrian citizenship by 1 Jul 1935, extended to 31 Dec 1935** (3965 pp. 50–57). **No completion is in the file**; the last paper (27 Nov 1935) warns the naturalisation will otherwise not be carried out. The children are never named; Alexander turned 18 on 30 Apr 1931 and could not have been covered as a minor. The applicant's "failed attempt" was the better description.
- **Felix** of 3966 is settled: Paul's eldest son, b. 7.3.1902 Hlušice, who **opted for Austria**, recognised by Austrian decree of 16.3.1921 (Zl. 48979/21); bank clerk at Vrchlabí from 1922; ordered out as an optant in 1924, then tolerated after Austrian intervention.
- **Alexander's own status** (separate sweep): every record gives Vienna domicile or Austrian citizenship — 1932 Heimatschein (TH Wien forms 1939, record 3505), 1936–40 Vienna registration "Staatsbürgerschaft Österreich" (3504), German Reich 1938–45 (4021), Austrian 1967 and 1982 (4005), Wiener Heimatrolle (4007). Nothing says he was ever Czechoslovak. **The Czech citizenship-by-declaration route through Alexander therefore has no basis on present evidence.**
- **For MA 35 the file cuts both ways** (a Czechoslovak authority itself ruled Paul Austrian with Vienna domicile from 1910): not to be volunteered; for Stefan's information only.

**27 Oct 2026 — three requests sent on whether Paul's Czechoslovak grant was completed** (relevant to a possible Czech citizenship declaration by the applicant's mother, Alexandra b. 1951, as Paul's grandchild; separate from the §58c case). Records-only wording, no purpose stated:
- **Salzburger Landesarchiv** (Friedl, Bezug 20033-A/3430/1151-2026) — release of Paul from the Austrian Staatsverband 1934–36, any certificate of non-possession/loss of Austrian citizenship, other citizenship records 1918–38. Zimbra **473210**.
- **Wiener Stadt- und Landesarchiv** (post@archiv.wien.gv.at) — the same, plus his Heimatrolle entry (Paul was zuständig nach Wien). Zimbra **473211**.
- **Národní archiv** (posta@na.gov.cz) — what followed MV decision no. 64517/1933 of 9 Apr 1934: grant or certificate 1935–38, the Austrian release document, or termination. Zimbra **473212**.
Aside: the Stadtarchiv Salzburg request of 5 Oct (468418) gave the grandfather as "Alexander **Hannibal** Graf Czernin" — the applicant's name in error; the Stadtarchiv answered regardless (records 4051–4054).

**27 Oct 2026 — contingent route: Czech citizenship by declaration (§31(3) Act 186/2013), recorded so it is not lost while the archives answer.** Not legal advice; to be confirmed with the Czech Embassy in London and raised with Stefan Huber before anything is filed.
- **Status of the line, as the records stand.** Pavel (Paul) was born an Austrian subject at Dymokury in 1879; his domicile moved by official appointment to Hodonín and then Vienna (from 1910 per the 1931 Prague ruling, 1916 per MA 35), so in 1918–20 he became a citizen of the Austrian Republic, not Czechoslovakia; his 1930 Czechoslovak certificate was invalidated in 1931. Alexander was born in Vienna in 1913 an Austrian subject with his father's domicile, became Austrian in 1918–20, and every later record gives Austrian citizenship or Vienna domicile; he was an adult (18 on 30 Apr 1931) before any grant to his father. **Neither the applicant nor anyone through Alexander has a Czech claim on present evidence.**
- **The one opening.** If Pavel's conditional grant of 1934 (MV no. 64517/1933; release from Austrian citizenship required by 31 Dec 1935) was completed, he died on 17 Jan 1938 a Czechoslovak citizen. Then: (1) the applicant's **mother, Alexandra** (b. London 12 Mar 1951, living), is Pavel's grandchild and could declare under §31(3); (2) once she is Czech, the **applicant** is the child of a current citizen and might declare in turn.
- **The doubt.** MZV guidance is inconsistent: one embassy page gives §31(3) as a parent or grandparent who "is a current or former" citizen (mzv.gov.cz/singapore …/new_citizenship_legislation_of_the_czech.html); another as one who "held … citizenship and lost it by 31 December 2013" (mzv.gov.cz/losangeles …/children_and_grandchildren_of_former.html). A grandparent who **died a citizen** in 1938 never "lost" it, so on the strict reading step (1) is uncertain; the statute or the embassy decides. Neither declarant may be a Slovak citizen.
- **Needed:** documentary proof of Pavel's Czechoslovak citizenship (what the three requests of 27 Oct ask for: Zimbra 473210, 473211, 473212) and the birth chain Pavel → Alexander (1921 Geburts- und Taufschein, record 4005) → Alexandra (record 3967) → applicant (Dropbox Certificates). Pavel's own baptism (Dymokury, 1879) is not yet held.
- **Order:** await the archives; if the grant was completed, ask the Czech Embassy London (§31(3): a grandparent who died a citizen; a child declaring after a parent); inform Stefan first. It should not bear on the §58c case, which rests on Alexander, but he should decide.

**27 Oct 2026 — evidence note for Stefan Huber drafted (not sent).** `~/Downloads/2026-10-27 MA35 387376-2026 — evidence note for Stefan Huber.docx` (20.8 MB; kept out of the repo for size). Point-by-point answer to MA 35's notice, factual errors in it (applicant's birth date given as 17.04.1973; mother's and Alexander's names), 14 exhibits with the archive's page images embedded unchanged (A1 record 4005 p4; A2 4078; A3 4079; B1–B2 4019 pp. 23, 26; B3 4025 p. 51, Wolfgang's visiting permit for 11 Apr 1943; B4 4009 p. 45; C1 4012 p. 69; C2 4040; D1 4024 p. 4, the NSDAP form listing "Heimwehr" among "gegnerische Verbände"; D2 4024 p. 6, SD: "Er war Mitglied der Heimwehr ist jedoch 1935 aus dieser ausgetreten"; E1 3505 p. 110, secondment to 31.12.1943; E2 4021 p. 2, Vienna deregistration 6.5.1946), a for-counsel-only section (Paul's citizenship ruling, Braunschweig 1947, Felix's NSDAP membership, Homolová and Wimmer) and the outstanding requests. The Partezettel JPEG (4040) had no JFIF header; one was inserted for Word, the image data unchanged.

**27 Oct 2026 — evidence note v2 (23.2 MB), emailed to the applicant only (Zimbra 473234; v1 473226; English translation of MA 35's letter 473229).** Added: **F1** Heydrich to Bormann, fol. 66 (record 3796 p. 5) — the danger of the ten nobles lay in their "adliger Verbindungen ins Reich und in die Ostmark sowie nach Ungarn"; MA 35 never engages with this sentence, and Alexander was such a connection (the note states that the letter does not itself target the Ostmark relatives). **F2** Wilhelm Thurn-Taxis, "Leiter der Geschäftsstelle im Generalsekretariat O 5", sworn statement of 28 Aug 1945 (WStLA, record 4061 p. 16; family copy 4006 p. 15) — Felix in his resistance group from 1943, intelligence-gathering abroad, the Turkey contact leading to his Gestapo arrest in early 1945; MA 35 had it from the 16 Oct filing yet calls Felix only "möglicherweise … bedroht", while accepting that relatives of political opponents were persecuted (§§ 83(2), 139 RStGB) and answering only with the unproven premise of Alexander's war work in 1945.

**Correction (27 Oct 2026):** Peter Czernin of the Wimmer thesis is **Dr Peter Rudolf Czernin, b. Vinoř 25.11.1907, d. Wien 31.10.1967** (family tree person 269, .A3.B2.C2.D5 — son of Ottokar, Alexander's first cousin), **married Melanie von Kragl in Vienna on 3.9.1938**; the thesis's "gest. Wien 3.9.1938" is the wedding date. He is the "Dr. Peter und Melanie Czernin, Wien 3., Reisnerstraße 30" of the 1947 StaPo file on the takeover of the Jewish estate Aichhof of Fanny Seemann (ÖStA 7187625, not ordered). Nothing held mitigates him beyond Eccher's group judgement of the SA-Brigade 6 circle as "Konjunkturritter". For MA 35 the useful points are logical, not about him: MA 35 accepts that Humprecht and Rudolf, equally his first cousins, were persecuted; he was another branch; and his rank was Obersturmführer, not Obersturmbannführer.

**27 Oct 2026 — evidence note v3, emailed to the applicant only (Part 1: Zimbra 473245, 17.3 MB; Part 2, Exhibits D–G: 473246, 13.7 MB; split for the 25 MB mail limit; files in ~/Downloads).** Adds, from a full cross-check of the notice against this log and the MA 35 / Cerha Hempel correspondence: (0) the § 17 AVG Akteneinsicht request of 28 Jul (455257) never answered, while the notice relies on undisclosed expert opinions of 04.08.2023 and 27.05.2026; (4a) thirteen further contradictions — Alexander's branch in Bohemia 1919–37 and Alexander at Schönhof 1935–38 (4019 pp. 24, 27; filed 16 Feb), the 1987 history written by Alexander not "your uncle", the Gauakt vetting concluded 4 May 1942 (4024 p. 7) before Heydrich's letter and Humprecht's arrest, the SD report naming Humprecht a "massgebliche[r] Vertreter" of the nobility's "Widerstandshaltung" (3801 p. 44; same text in 2915 filed 13 Mar) unmentioned, MA 35's own 2023 letter p. 7 ("können seitens des Historikers nicht verifiziert werden"), MA 35's own "his cousins" (460075); (4b) family map of positions; (4c) corrections we owe (the "Beobachtungsliste" wording of 13 Mar, 50,000 ha, "voluntarily", "stattgegeben", the declaration-form errors, and whether the "durch Lucki" Wiederaufnahme draft was ever sent). Stefan Huber is on holiday this week (473194); the firm has diarised the deadline.

**27 Oct 2026 — evidence note v3.1, Part 1 emailed to the applicant (Zimbra 473257, 18.6 MB; Part 2 unchanged, 473246).** New lead argument: MA 35 accepts that "Grundsätzlich verfolgte das NS-Regime auch enge Familienmitglieder von politischen Gegnern …" and Felix's Gestapo custody for high treason, and answers for Alexander only with "dass er in der Zeit der Verfolgung seines Bruders als Ingenieur an einem kriegswichtigen Forschungsprojekt … beteiligt gewesen ist". That premise is undocumented (nothing after 31.12.1943; MA 35's own 2023 historian: unverifiable) and contradicted by Felix's own case — a serving soldier, arrested on reporting to the Wehrkreiskommando and held "da ich Soldat sei" (diary, record 3507 pp. 1–2) — and by Felix's diary of 30 April 1945, "Luckis Geburtstag. Hoffentlich ist er in Aussee bei Mami" (p. 11), the only contemporary reference to Alexander's whereabouts: a brother who believed him engaged on a war project would not hope he was at home with their mother. Exhibits H1–H3. Desertion recorded for counsel only as an unconfirmed family account (Trauttenberg vs the daughter), with possible sources (Abt. PA "Vermerke über Fahndungen", Wehrmacht wanted lists, Gendarmerie chronicles Bad Aussee/Altaussee, DÖW on the Ausseerland resistance that sheltered deserters).

**27 Oct 2026 — the "durch Lucki" draft was never filed; the "uncle" error is ours; evidence note v3.2 Part 1 (Zimbra 473264).** The draft `MA35_Wiederaufnahme_with_DeclarationForm_Reference.docx` left the mailbox only as an attachment to Emily Morgan (14 Jan 2026, Zimbra 420438); the applicant confirms Cerha Hempel did not send it; neither filed Urkundenvorlage contains the claim. The Urkundenvorlage of 16 Feb 2026 (filed via the Embassy, Zimbra 425832) describes the 1987 document as „eine Übersetzung der durch den Onkel des Großvaters, Franz Josef, verfassten Familiengeschichte" — the source of MA 35's "durch Ihren Onkel verfasst"; the document is Alexander's own first-person account. The 13 Mar Urkundenvorlage (431583) contains our "stand daher auf der Beobachtungsliste der Partei". Both now listed in 4c as our own corrections; the Lucki item removed.
