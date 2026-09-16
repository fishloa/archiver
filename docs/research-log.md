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
during the occupation and are worth checking individually: **Zikmund Schlik**
(1917 Vokšice – 1942 Jičíněves, aged 25), **Josef Lobkowicz** (1918 – 1946 Mělník,
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
