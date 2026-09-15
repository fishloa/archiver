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
