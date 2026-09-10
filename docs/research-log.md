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
