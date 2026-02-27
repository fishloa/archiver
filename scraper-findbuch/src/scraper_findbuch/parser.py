"""Parse findbuch.at search results and record detail pages."""

import re
import logging
from bs4 import BeautifulSoup

log = logging.getLogger(__name__)


def parse_search_results(html: str) -> list[dict]:
    """Parse search results page. Returns list of result dicts.

    Each result dict contains:
    - detail_url: link to record detail page
    - title: text from the result listing
    """
    soup = BeautifulSoup(html, "html.parser")
    results = []

    # findbuch.at wraps results in mm_filter_list or mod_metamodel_list
    # When logged in, results appear as links inside the list container
    # Try multiple strategies

    # Strategy 1: Look for links inside mod_metamodel_list
    list_div = soup.find("div", class_="mod_metamodel_list")
    if list_div:
        for link in list_div.find_all("a", href=True):
            href = link.get("href", "")
            text = link.get_text(strip=True)
            if not href or not text:
                continue
            # Skip pagination, navigation, and column-sort links
            if "/page/" in href or "searchterm" in href or "orderBy" in href:
                continue
            if href.startswith("/"):
                href = "https://www.findbuch.at" + href
            results.append({"detail_url": href, "title": text})

    # Strategy 2: Look for table rows with links
    if not results:
        for row in soup.find_all("tr"):
            link = row.find("a", href=True)
            if link:
                href = link.get("href", "")
                text = link.get_text(strip=True) or row.get_text(strip=True)[:100]
                if href and "/page/" not in href:
                    if href.startswith("/"):
                        href = "https://www.findbuch.at" + href
                    results.append({"detail_url": href, "title": text})

    # Strategy 3: Any link that looks like a findbuch detail page
    if not results:
        # Only look inside the main content area, skip footer/nav
        content = soup.find("div", id="main") or soup.find("main") or soup
        for link in content.find_all("a", href=True):
            href = link.get("href", "")
            text = link.get_text(strip=True)
            # Only accept links to findbuch.at pages (not external sites)
            if (href and text and len(text) > 5
                    and "/page/" not in href
                    and "searchterm" not in href
                    and "login" not in href.lower()
                    and "registration" not in href.lower()
                    and "data-protection" not in href
                    and "imprint" not in href
                    and "contact" not in href
                    and "site-map" not in href
                    and "forgot" not in href.lower()
                    and not href.startswith("http://")
                    and not href.startswith("https://www.facebook")
                    and not href.startswith("https://www.instagram")
                    and not href.startswith("https://www.twitter")
                    and not href.startswith("https://twitter")
                    and not href.startswith("https://x.com")
                    and (href.startswith("/") or href.startswith("https://www.findbuch.at/"))
                    and href.count("/") >= 2):
                if href.startswith("/"):
                    href = "https://www.findbuch.at" + href
                results.append({"detail_url": href, "title": text})

    # Check for login-required message
    if not results:
        if "registered users" in html.lower() or "registrierten" in html.lower():
            log.warning("Results hidden behind login — FINDBUCH_USERNAME/PASSWORD required")

    log.debug("Parsed %d search results from page", len(results))
    return results


def get_result_count(html: str) -> int:
    """Extract total number of results from search page.

    Looks for pattern: '<strong>N results</strong>' or 'N Ergebnisse'.
    """
    match = re.search(r'<strong>\s*(\d+)\s*(?:results?|Ergebnisse?)\s*</strong>', html, re.IGNORECASE)
    if match:
        return int(match.group(1))
    match = re.search(r'(\d+)\s+(?:results?|Ergebnisse?)', html, re.IGNORECASE)
    if match:
        return int(match.group(1))
    return 0


def get_total_pages(html: str) -> int:
    """Extract total number of pages from search results pagination.

    Returns page count. If no pagination found, returns 1.
    """
    soup = BeautifulSoup(html, "html.parser")

    # Look for pagination links like /page/N
    max_page = 1
    for link in soup.find_all("a", href=re.compile(r"/page/\d+")):
        href = link.get("href", "")
        match = re.search(r"/page/(\d+)", href)
        if match:
            page_num = int(match.group(1))
            max_page = max(max_page, page_num)

    # Fallback: derive from result count text (e.g. "122 results", 11 per page)
    if max_page == 1:
        count = get_result_count(html)
        if count > 0:
            per_page = 11  # findbuch.at uses 11 results per page
            max_page = (count + per_page - 1) // per_page
            log.debug("Derived %d pages from %d results", max_page, count)

    return max_page


def parse_detail_page(html: str, detail_url: str = "") -> dict:
    """Parse a record detail page. Returns metadata dict.

    findbuch.at detail pages use various layouts depending on record type.
    Fields: surname, forename, profession, address, dateOfBirth,
    nationality, religion, race, spouse_info, assets, file_number, archive_info.
    """
    soup = BeautifulSoup(html, "html.parser")
    metadata = {"detail_url": detail_url}

    # Map of label patterns to field names (German + English)
    label_map = [
        (r"nachname|surname|familienname", "surname"),
        (r"vorname|forename|first\s*name", "forename"),
        (r"beruf|profession|occupation", "profession"),
        (r"adresse|address|wohn", "address"),
        (r"geburt|birth|geb\.", "dateOfBirth"),
        (r"nationalit|nationality|staatsb", "nationality"),
        (r"religion|konfession|glauben", "religion"),
        (r"rasse|race", "race"),
        (r"ehegatt|spouse|gatt", "spouse_info"),
        (r"verm.gen|asset|aktiva|besitz", "assets"),
        (r"akten|file|geschäfts", "file_number"),
        (r"archiv|archive|fundstelle", "archive_info"),
        (r"bemerk|remark|anmerk|note", "remarks"),
    ]

    def match_field(label_text):
        for pattern, field_name in label_map:
            if re.search(pattern, label_text, re.IGNORECASE):
                return field_name
        return None

    # Strategy 1: Definition lists (dt/dd)
    for dt in soup.find_all("dt"):
        label = dt.get_text(strip=True).rstrip(":")
        dd = dt.find_next_sibling("dd")
        if not dd:
            continue
        value = dd.get_text(strip=True)
        if not value:
            continue
        field = match_field(label)
        if field:
            if field in metadata and metadata[field]:
                metadata[field] += "; " + value
            else:
                metadata[field] = value

    # Strategy 2: Table with label/value cells
    for row in soup.find_all("tr"):
        cells = row.find_all(["th", "td"])
        if len(cells) >= 2:
            label = cells[0].get_text(strip=True).rstrip(":")
            value = cells[1].get_text(strip=True)
            if label and value:
                field = match_field(label)
                if field:
                    if field in metadata and metadata[field]:
                        metadata[field] += "; " + value
                    else:
                        metadata[field] = value

    # Strategy 3: Labeled divs/spans
    for elem in soup.find_all(["div", "span", "p"], class_=re.compile(r"label|field|key", re.I)):
        label = elem.get_text(strip=True).rstrip(":")
        # Value is the next sibling or parent's next child
        value_elem = elem.find_next_sibling(["div", "span", "p"])
        if value_elem:
            value = value_elem.get_text(strip=True)
            if label and value:
                field = match_field(label)
                if field:
                    metadata[field] = value

    # Strategy 4: Extract from page title/heading
    if not metadata.get("surname"):
        h1 = soup.find("h1")
        if h1:
            text = h1.get_text(strip=True)
            # Common format: "SURNAME, Forename"
            if "," in text:
                parts = text.split(",", 1)
                metadata["surname"] = parts[0].strip()
                metadata["forename"] = parts[1].strip()

    log.debug("Parsed detail: %s %s", metadata.get("surname", "?"), metadata.get("forename", "?"))
    return metadata
