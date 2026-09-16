**To:** berlin@bundesarchiv.de
**Status:** sent 16 September 2026 (Zimbra 463985), with the signed Benutzungsantrag attached
**Subject:** NSDAP-Mitgliederkartei and R 8119F, Felix / Wolfgang / Jan Czernin

The Bundesarchiv will not search anything without a **hand-signed
Benutzungsantrag**, and the request has to go to `berlin@bundesarchiv.de`. Two
letters now say so:

- 23 December 2025, ref **DR 4 – 2023/0002#0185#0001** (in the Dropbox Austria
  folder): the Alexander Czernin enquiry cannot proceed without the form; fees are
  EUR 5 per quarter hour; and "Digitalisierung on Demand" gives **up to ten files
  per Benutzungsthema per calendar year free of charge**.
- 16 September 2026, in reply to today's enquiry (Zimbra 463966): the address
  `Anmeldung-Zeit@bundesarchiv.de` is only for booking a reading-room visit —
  "Bitte senden Sie Ihre Anfrage zusammen mit einem ausgefüllten und eigenhändig
  unterschriebenen Benutzungsantrag an die Adresse berlin@bundesarchiv.de."

So the form has been the blocker on the whole Bundesarchiv line since December,
which explains why the Alexander enquiry stalled and was answered only with
"search invenio yourself" in August.

The blank form came attached to 463966. It is a fillable AcroForm and has been
completed as far as it can be without the applicant:

| Field | Value |
|---|---|
| 1. Vor- und Zuname | Alexander Hannibal Czernin Fishlock |
| 2. Adresse | Icomb Place, GL54 1JL, Icomb, Cheltenham, Großbritannien |
| 3. Beruf | Company Director |
| 4. Staatsangehörigkeit | britisch |
| 5. E-Mail | alex.fishlock@racingjag.com |
| 6. Benutzungsthema | Familienforschung Czernin von Chudenic 1938–1950: NSDAP-Mitgliederkartei (BDC) for Felix (7.3.1902), Wolfgang (4.8.1903) and Jan (8.3.1910); R 8119F Dresdner Bank and Bankenkommissar Belgien for Felix; earlier file DR 3.02 – 2023/0002#0185#0001 |
| 7. Benutzungszweck | privat — **Beweismittel** and **Genealogie** |
| 11. Already worked at the Bundesarchiv | nein *(assumed: correspondence yes, reading room no)* |
| 12. Name/topic may be disclosed to other users | nein |
| 13. Newsletter | nein |
| Ort / Datum | Icomb, Cheltenham, Großbritannien / 16.09.2026 |

The applicant added his occupation and signature to the same file in the Dropbox
Austria folder, and it went to `berlin@bundesarchiv.de` the same day (Zimbra
463985). The covering letter quotes DR 3.02 – 2023/0002#0185#0001, asks the
Mitgliederkartei question for all three brothers with the identifying detail for
each, asks about R 8119F, the Reichswirtschaftsministerium and the
Bankenkommissar Belgien for Felix, claims the **ten free Digitalisierung-on-Demand
files** the December letter offered, and caps costs at EUR 200 with prior notice
above that. It says in terms that an adverse answer is wanted.

**Sending a signed form needs the local CLI.** The cloud Zimbra tool cannot attach
a local file — it takes base64 or a reference to another message's attachment, and
a 936 KB PDF is 1.3 million base64 characters, far too large to pass through a
tool call. `zimba email send` (see the Zimba CLI note in memory) takes file paths;
it accepts a JSON payload on stdin with `to`, `subject`, `body` and `attachments`.
The attachment went out under the working filename `ba_signed.pdf`, which should
have been renamed to something descriptive first.

Filling this form programmatically: pypdf's `update_page_form_field_values` sets
text fields but leaves radio groups unmarked, because a radio's mark lives on the
kid annotation. The kid's `/AS` has to be set to the export value (`/ja`, `/nein`)
and the parent's `/V` with it, or the printed form shows empty boxes. The
Benutzungsthema box holds about three lines before it clips, and the Postleitzahl
field is narrow — the town belongs in the separate `Ort` field.
