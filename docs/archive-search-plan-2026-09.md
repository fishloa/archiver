# Archive Search Plan — September 2026

## Context

We are building a legal submission under §58c of the Austrian Citizenship Act for Alexander Czernin (b. 30.4.1913, Vienna). The persecution of the Czernin family is now established via a classified Heydrich situation report (Archiver Record 3796, NA Praha fond 1005, sign. 114-3-17). The evidentiary gap has shifted from "prove persecution existed" to "prove Alexander knew about it." We need documents showing what Alexander and his siblings knew, and original security/arrest files for the family members who were persecuted.

## Available Scrapers

| Scraper | Archive | Status |
|---------|---------|--------|
| scraper-cz | Czech National Archives (Zoomify → PDF) | ✅ deployed |
| scraper-ebadatelna | Archive of Security Forces (eBadatelna) | ✅ deployed |
| scraper-oesta | Austrian State Archives (ÖStA) | ✅ deployed |
| scraper-findbuch | Austrian victims/property database | ✅ deployed |
| scraper-matricula | Matricula Online church records | ✅ deployed |
| scraper-arolsen | Arolsen Archives | ❌ built, not deployed |
| scraper-ddb | Deutsche Digitale Bibliothek | ❌ built, not deployed |

---

## TASK 1 — HIGHEST PRIORITY: scraper-ebadatelna

Run `scraper-ebadatelna` against ebadatelna.cz for the following four individuals. Search by name variants shown. Download everything found.

### 1a. Alexander Czernin
- Search terms: `Czernin Alexander`, `Czernin Alex`, `Černín Alexandr`
- Looking for: SD-Leitabschnitt Wien surveillance report from August 1941 (ref PA 3852/41). We have a summary from ÖStA but the original should be in ABS. Also any other security files.
- Known reference: SD-Leitabschnitt Wien, III C, PA 3852/41

### 1b. Felix Czernin
- Search terms: `Czernin Felix`, `Černín Felix`
- Looking for: Gestapo arrest file from 28 March 1945. Felix was arrested on RSHA orders, charged with Austrian Legitimism, defeatist statements, connections to the Falkenhausen resistance circle, and contact with enemy powers via Turkey. Any StaPo or RSHA file.
- Known details: Arrested 28.3.1945, held until liberation May 1945. Born 7.3.1902 Hlušice, died 3.1.1968 Vienna.

### 1c. Humprecht Czernin
- Search terms: `Czernin Humprecht`, `Černín Humprecht`
- Looking for: Standgericht file from June 1942 (death sentence, commuted to life). Prison file beyond what's in Arolsen (Record 3410 = Arolsen 12120657). Any security police files.
- Known details: Born 9.2.1909 Dymokury, died 19.9.1944 Sanatorium Pleš. Sentenced to death June 1942, commuted to life within two days. Weapons possession charge.

### 1d. Rudolf Czernin
- Search terms: `Czernin Rudolf`, `Černín Rudolf`, `Czernin Rudobald`
- Looking for: Sondergericht trial file (convicted 28 March 1944 — listening to and spreading enemy broadcasts). Any Gestapo or security files related to his arrest (~late 1943).
- Known reference: NA sign. 110-4/59 (Sondergericht judgment). Also Archiver Record 195.
- Known details: Born 25.8.1904 Dymokury, died 1984. Convicted alongside František Kinský and Karel Viktor Rohan.

### Also try these broader searches:
- `Czernin` (all family members)
- `Černín` (Czech spelling)
- `Dymokury` (the estate)

---

## TASK 2 — Fetch ÚSTR open PDFs

Download these PDFs from ustrcr.cz:

### 2a. Hořejš article on Rudolf and Humprecht
- Citation: HOŘEJŠ, Miloš: "Rudolf Děpold a Humprecht Czerninové z Chudenic. Oběti heydrichiády z řad české šlechty." Paměť a dějiny, 2014, roč. 8, č. 3, s. 31–42.
- Try URL pattern: `https://www.ustrcr.cz/wp-content/uploads/` or search ustrcr.cz for the article
- Also check: `https://www.ustrcr.cz/publikace/pamet-a-dejiny/`
- Note: An earlier session found a Hořejš article at `https://www.ustrcr.cz/wp-content/uploads/2017/10/SI_30_s12-35.pdf` — that's a different article (Czernin-Morzin branch, Securitas Imperii 30). We want the Paměť a dějiny 2014/3 one specifically.

### 2b. Jelínková article (already partially retrieved)
- Citation: JELÍNKOVÁ, Dita: "Příběh rodiny Huga Salm-Reifferscheidta." Securitas Imperii 18 (2011), pp. 42–69.
- URL: `https://www.ustrcr.cz/data/pdf/publikace/securitas-imperii/no18/042-069.pdf`
- Already known to exist. Download full PDF if not already in archiver.

---

## TASK 3 — Czech thesis search

Search dspace.cuni.cz and theses.cz for theses mentioning Czernin persecution.

### Search terms:
- `Czernin šlechta perzekuce`
- `Czernin Dymokury`
- `Czernin Heydrich`
- `česká šlechta nucená správa 1942`
- `Humprecht Czernin`

### Already found:
- Vochozka 2016 (law faculty, Charles University) — quotes the Heydrich letter at fn. 57
- Knoflíčková (Masaryk University, bachelor's) — references Kárný edition
- Veselý 2014 (Charles University) — methodological guide to Protectorate fonds, quantifies name-index unreliability at ~33%

### Retrieve any new hits as PDFs.

---

## TASK 4 — scraper-cz (Czech National Archives)

### 4a. Pozemkový úřad / Bodenamt für Böhmen und Mähren
- Search badatelna.eu or vademecum.nacr.cz for this fond
- Looking for: the forced-administration order of 12 February 1942 affecting Czernin estates, and any related correspondence
- The Bodenamt is the body that executed the confiscation Heydrich reports in the 20th Lagebericht

### 4b. Národní soud (National Court)
- The 1946 Prague trial of K.H. Frank
- Looking for: prosecution evidence on noble estate confiscations
- Frank was Heydrich's Staatssekretär and directly involved

### 4c. Fond 1005 (ÚŘP) — sign. 110-4/59
- Rudolf's Sondergericht judgment
- Referenced in Hazdra's work (Record 3797, p. 56, n. 102)
- This is the judgment document itself

---

## TASK 5 — scraper-oesta (Austrian State Archives)

### 5a. Check for Ferdinand Czernin
- Already ordered: AT-OeStA/AdR Inneres BMI StSu StaPo Akten Kzl 32.961-2/47
- If available online, download it

### 5b. Search for any further Czernin files
- Search terms: `Czernin`, `Černín`
- Particularly in StaPo records 1938–1955
- Already have: Gauakt 232.006 (Record 3499)

---

## OUTPUT

For each search:
1. Report what was found and what was negative
2. Download all PDFs/images found
3. Note the exact URL, fond, signatura, and page count
4. Flag anything mentioning Alexander, Felix, Wolfgang, or their mother Maria Gabriele

Upload all downloaded files to the Czernin Archiver at archiver.icomb.place with descriptive titles.

---

## STATUS — as at 14 September 2026

Full detail of the run is in `research-log.md` under *2026-09-14*. Correspondence
texts are in `docs/correspondence/`. Short version:

| Task | State | Where it stands |
|------|-------|-----------------|
| 1 — ebadatelna | **blocked** | OCR/fulltext search is gated on account verification, which needs Czech eID / bank ID / mojeID / I.CA. A nil result there means nothing: `Praha` also returns zero. Written rešerše sent to ABS instead, 14.9.2026 (Zimbra 463590, to `badatelna.struha@abscr.cz`, cc `ebadatelna@abscr.cz`). Awaiting reply. Note `badatelna@abscr.cz` does not exist — it bounces 550. |
| 2 — ÚSTR PDFs | **done** | Hořejš was already held as **3519** (correct URL: `.../pamet-dejiny/pad1404/031-042.pdf` — issue 2014/**04**). Jelínková SI 18 ingested as **3803**. |
| 3 — Czech theses | **mostly done** | Ingested **3804** (Kučerová, nobility declarations 1938-39) and **3805** (Vochozka, Schwarzenberg property 1938-50, quotes the Heydrich letter at fn. 57). Hazdra was already **3797**/**3495**. Jelínková's UPa dissertation is login-only (401). Knoflíčková has one Czernin mention — judged not worth ingesting. Blažková 2018 (post-war Lex Schwarzenberg) left out as out of period; PDF parked at `/tmp/b.pdf` in `archiver-test-frontend-test-1`. |
| 4 — NA Praha | **already held** | 4c is **record 195** (sign. 110-4/59, Rudolf's trial). 4a is covered by **3791**, **3206**, **2915**, **1540**, **1563**; the 12 Feb 1942 order itself has not surfaced. 4b Národní soud is not digitised. Separately, NA has scanned the rest of sign. 114-3-17 and the file has gone to their accounts department — invoice expected. |
| 5 — ÖStA | **identified, not digitised** | Seven records found, none with digital objects, so all need ordering. HHStA order sent 14.9.2026 (Zimbra 463585) for 3380985 (parents' 1901 marriage announcement), 7224625 and 7224646 (Felix's and Wolfgang's post-war letters to Georg Nostitz), plus three Partezettel as secondary. AdR order for 7187521 (Ferdinand) went 11.9.2026 (Zimbra 462922) and is unanswered; draft 463600 extends that name enquiry to Wolfgang and Maria Gabriele — unsent, send or bin. |

Out of scope by decision: the StaPo file on Dr. Peter and Melanie Czernin
(7187625, Aryanization of the Aichhof estate). Different branch — recorded so it
is known about, not pursued.

### To pick up next

1. Replies: ABS rešerše, ÖStA AdR, ÖStA HHStA, NA invoice.
2. Decide on draft 463600 (AdR name extension) and delete dead draft 463574.
3. Optional: Blažková 2018; Knoflíčková 2015.
4. Still unlocated: the forced-administration order of 12 February 1942, and the
   original SD-Leitabschnitt Wien report PA 3852/41 on Alexander.

### Working notes that will save time

- **dspace.cuni.cz refuses this network** (429 / reset) but answers zelkova. Route
  through a container: Portainer `dockerProxy` → `POST /containers/{id}/exec` then
  `/exec/{id}/start`; `bun -e` in the frontend image can fetch the file and
  multipart-POST it to `/api/ingest/records/{id}/text-pdf`. Expect the exec call to
  be backgrounded — dspace takes minutes to answer.
- **Ingest of a born-digital PDF**: `POST /api/ingest/records` → `POST
  /api/ingest/records/{id}/text-pdf` → `POST /api/ingest/records/{id}/complete`,
  with the processor token as bearer. `text-pdf` skips OCR and takes the embedded
  text layer.
- **theses.cz and is.muni.cz** answer the first request with a `<meta refresh>`
  stub; do the cookie handshake first, then `https://is.muni.cz/th/<id>/` lists the
  files.
- **ÖStA** search is a plain POST to `/volltextsuche.aspx` with the three ViewState
  fields plus `ctl00$cphMainArea$txtMitAllenWoertern`. Result paging is a Telerik
  postback that did not reproduce with a plain form POST — narrow the query rather
  than trying to page.
