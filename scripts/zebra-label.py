#!/usr/bin/env python3
"""Print document labels on Zebra ZD621 (300 DPI, 6x4 inch labels).

Printer:
    Model:      Zebra ZD621
    Resolution: 300 DPI (12 dots/mm)
    Label size: 6x4 inches (portrait: 4" wide x 6" long)
    Print width: 1200 dots (4 inches)
    Label length: 1782 dots (~6 inches)
    IP:         192.168.30.4 (mDNS: zebra-zd621.local)
    Port:       9100 (raw TCP)
    Location:   Alex Office Cupboard
    Firmware:   V93.21.46Z
    Protocol:   ZPL II

Usage:
    # Print a single label
    python3 scripts/zebra-label.py --record 195 \
        --title "Criminal case file 110-4/59, Graf Rudolf ..." \
        --archive "Czech National Archives, fond 1799" \
        --pages 53

    # Print from a JSON file (array of label objects)
    python3 scripts/zebra-label.py --file labels.json

    # Preview ZPL without printing
    python3 scripts/zebra-label.py --record 195 --title "..." --dry-run

Label fields:
    --header1   Line 1 of header (default: §58 Verfolgung — New Evidence)
    --header2   Line 2 of header (default: grandfather's full name + DOB)
    --applicant Footer applicant line (default: Alexander Hannibal Czernin Fishlock)
    --record    Record number
    --title     Document title
    --archive   Source archive
    --pages     Page count (number or string like "53 pages")
    --printer   Printer IP (default: 192.168.30.4)
    --port      Printer port (default: 9100)
"""

import argparse
import json
import socket
import sys
import time

DEFAULT_HEADER1 = "§58 Verfolgung — New Evidence"
DEFAULT_HEADER2 = "Graf Alexander Friedrich Josef Paul Maria Czernin von Chudenitz, b. 30.4.1913"
DEFAULT_APPLICANT = "Applicant: Alexander Hannibal Czernin Fishlock"
DEFAULT_PRINTER = "192.168.30.4"
DEFAULT_PORT = 9100

# Zebra ZD621 300 DPI, 6x4 inch label = 1200 x 1782 dots
PRINT_WIDTH = 1200
LABEL_LENGTH = 1782
MARGIN = 40
CONTENT_WIDTH = PRINT_WIDTH - 2 * MARGIN


def generate_zpl(record, title, archive, pages, header1=None, header2=None, applicant=None):
    h1 = header1 or DEFAULT_HEADER1
    h2 = header2 or DEFAULT_HEADER2
    app = applicant or DEFAULT_APPLICANT

    pages_str = pages if isinstance(pages, str) else f"{pages} pages" if pages else ""
    if pages_str and not pages_str.endswith("page") and not pages_str.endswith("pages"):
        pages_str += " pages" if int(str(pages_str).split()[0]) != 1 else " page"

    return f"""^XA
^PW{PRINT_WIDTH}
^LL{LABEL_LENGTH}
^LH0,0
^CI28
^FO{MARGIN},40^A0N,60,60^FB{CONTENT_WIDTH},2,0,L^FD{h1}^FS
^FO{MARGIN},115^A0N,38,38^FB{CONTENT_WIDTH},2,6,L^FD{h2}^FS
^FO{MARGIN},210^GB{CONTENT_WIDTH},8,8^FS
^FO{MARGIN},250^A0N,160,160^FB{CONTENT_WIDTH},1,0,L^FDRecord {record}^FS
^FO{MARGIN},450^A0N,60,60^FB{CONTENT_WIDTH},5,10,L^FD{title}^FS
^FO{MARGIN},750^A0N,48,48^FB{CONTENT_WIDTH},3,8,L^FD{archive}^FS
^FO{MARGIN},1560^GB{CONTENT_WIDTH},4,4^FS
^FO{MARGIN},1590^A0N,60,60^FD{pages_str}^FS
^FO{MARGIN},1680^A0N,38,38^FD{app}^FS
^XZ"""


def send_to_printer(zpl, printer_ip, printer_port, dry_run=False):
    if dry_run:
        print(zpl)
        return True
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(5)
        s.connect((printer_ip, printer_port))
        s.sendall(zpl.encode("utf-8"))
        s.close()
        return True
    except Exception as e:
        print(f"Error sending to printer: {e}", file=sys.stderr)
        return False


def main():
    parser = argparse.ArgumentParser(description="Print document labels on Zebra ZD621")
    parser.add_argument("--record", help="Record number")
    parser.add_argument("--title", help="Document title")
    parser.add_argument("--archive", help="Source archive")
    parser.add_argument("--pages", help="Page count")
    parser.add_argument("--header1", help=f"Header line 1 (default: {DEFAULT_HEADER1})")
    parser.add_argument("--header2", help=f"Header line 2 (default: grandfather's name)")
    parser.add_argument("--applicant", help=f"Applicant line (default: {DEFAULT_APPLICANT})")
    parser.add_argument("--file", help="JSON file with array of label objects")
    parser.add_argument("--printer", default=DEFAULT_PRINTER, help=f"Printer IP (default: {DEFAULT_PRINTER})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"Printer port (default: {DEFAULT_PORT})")
    parser.add_argument("--dry-run", action="store_true", help="Print ZPL to stdout instead of sending")
    args = parser.parse_args()

    labels = []

    if args.file:
        with open(args.file) as f:
            labels = json.load(f)
    elif args.record and args.title:
        labels = [
            {
                "record": args.record,
                "title": args.title,
                "archive": args.archive or "",
                "pages": args.pages or "",
            }
        ]
    else:
        parser.error("Provide --record and --title, or --file with a JSON array")

    for i, lab in enumerate(labels):
        zpl = generate_zpl(
            record=lab["record"],
            title=lab["title"],
            archive=lab.get("archive", ""),
            pages=lab.get("pages", ""),
            header1=args.header1 or lab.get("header1"),
            header2=args.header2 or lab.get("header2"),
            applicant=args.applicant or lab.get("applicant"),
        )
        ok = send_to_printer(zpl, args.printer, args.port, args.dry_run)
        status = "OK" if ok else "FAILED"
        print(f"[{i + 1}/{len(labels)}] Record {lab['record']}: {status}", file=sys.stderr)
        if len(labels) > 1 and i < len(labels) - 1:
            time.sleep(1)


if __name__ == "__main__":
    main()
