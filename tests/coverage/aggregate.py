#!/usr/bin/env python3
"""Independent line coverage: never average PHP, Python, and Java percentages."""
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

REPORTS = (
    ("PHP / SQLite", "php/sqlite/cobertura.xml", "cobertura"),
    ("PHP / MySQL", "php/mysql/cobertura.xml", "cobertura"),
    ("SQLite initializer / Python", "python/sqlite/coverage.xml", "cobertura"),
    ("File #1 JDBC / Java", "java/bio1/jacoco.xml", "jacoco"),
    ("File #2 JDBC / Java", "java/bio2/jacoco.xml", "jacoco"),
)


def percent(path: Path, fmt: str) -> str:
    root = ET.parse(path).getroot()
    if fmt == "cobertura":
        if root.tag != "coverage":
            raise ValueError(f"Unexpected coverage root: {root.tag}")
        covered, total = root.attrib.get("lines-covered"), root.attrib.get("lines-valid")
        if covered is not None and total is not None and int(total) > 0:
            return f"{100 * int(covered) / int(total):.1f}%"
        if "line-rate" not in root.attrib:
            raise ValueError("Missing line-rate")
        return f"{100 * float(root.attrib['line-rate']):.1f}%"
    if fmt == "jacoco":
        if root.tag != "report":
            raise ValueError(f"Unexpected JaCoCo root: {root.tag}")
        counter = next((node for node in root.findall("counter")
                        if node.attrib.get("type") == "LINE"), None)
        if counter is None:
            raise ValueError("Missing LINE counter")
        covered = int(counter.attrib["covered"])
        missed = int(counter.attrib["missed"])
        return f"{100 * covered / (covered + missed):.1f}%" if covered + missed else "N/A"
    raise ValueError(fmt)


def render(base: Path) -> str:
    lines = ["## BioServer source coverage", "", "| Component / backend | Line coverage |", "|---|---:|"]
    for title, name, fmt in REPORTS:
        path = base / name
        try:
            stat = percent(path, fmt) if path.is_file() else "Not generated"
        except (ET.ParseError, ValueError, OSError) as exc:
            stat = "Unreadable report"
            print(f"Unable to read {path}: {exc}", file=sys.stderr)
        lines.append(f"| {title} | {stat} |")
    lines += [
        "",
        "Source coverage is per component and backend; **no cross-language average is calculated**.",
        "Java executes real JDBC against MySQL only; SQLite JDBC support is not implemented yet.",
        "These metrics do not certify PS2 gameplay behavior or a real-data migration.",
    ]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    args = sys.argv[1:]
    required = "--require" in args
    args = [arg for arg in args if arg != "--require"]
    base = Path(args[0] if args else ".")
    print(render(base), end="")
    if required:
        missing = [str(base / name) for _, name, _ in REPORTS
                   if not (base / name).is_file()]
        if missing:
            print("Missing required coverage XML: " + ", ".join(missing), file=sys.stderr)
            raise SystemExit(1)
