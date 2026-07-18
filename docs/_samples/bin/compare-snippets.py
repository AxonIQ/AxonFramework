#!/usr/bin/env python3
"""Drift check for externalized documentation code samples.

Compares the code blocks of an AsciiDoc page in the working tree (where blocks
may be include::example$...[tag=...] references) against the literal blocks of
the same page in a given git revision. Include references are resolved the way
Asciidoctor resolves them: tag regions are extracted in file order, tag
directive lines are stripped, and indent=0 re-indentation is applied.

Usage:
    compare-snippets.py <page.adoc> [<git-revision>]

The git revision defaults to HEAD. Exit code is 0 when all blocks match,
1 when any block differs, 2 on usage or resolution errors.
"""

import difflib
import re
import subprocess
import sys
from pathlib import Path

SOURCE_BLOCK_RE = re.compile(r"^\[source,\s*java[^\]]*\]\s*$")
INCLUDE_RE = re.compile(r"^include::example\$(?P<path>[^\[]+)\[(?P<attrs>[^\]]*)\]\s*$")
TAG_DIRECTIVE_RE = re.compile(r"^\s*//\s*(tag|end)::\S+\[\]\s*$")


def extract_source_blocks(lines):
    """Return the bodies of all [source,java] blocks as lists of lines."""
    blocks = []
    i = 0
    while i < len(lines):
        if SOURCE_BLOCK_RE.match(lines[i]):
            # Find the opening ---- delimiter (allowing attribute lines in between).
            j = i + 1
            while j < len(lines) and lines[j].strip() != "----":
                j += 1
            if j >= len(lines):
                break
            body = []
            j += 1
            while j < len(lines) and lines[j].strip() != "----":
                body.append(lines[j])
                j += 1
            blocks.append(body)
            i = j
        i += 1
    return blocks


def find_examples_root(adoc_path):
    """Derive the Antora module examples directory from a page path."""
    parts = adoc_path.resolve().parts
    if "pages" not in parts:
        sys.exit(f"error: {adoc_path} is not under a pages/ directory")
    pages_index = len(parts) - 1 - parts[::-1].index("pages")
    return Path(*parts[:pages_index]) / "examples"


def resolve_include(line, examples_root):
    """Resolve an include::example$...[...] line to its rendered lines."""
    match = INCLUDE_RE.match(line)
    if not match:
        return None
    source_file = examples_root / match.group("path")
    if not source_file.is_file():
        sys.exit(f"error: include target not found: {source_file}")
    attrs = {}
    for attr in match.group("attrs").split(","):
        if "=" in attr:
            key, value = attr.split("=", 1)
            attrs[key.strip()] = value.strip()
    file_lines = source_file.read_text().splitlines()

    tags = attrs.get("tags") or attrs.get("tag")
    if tags:
        selected = []
        for tag in tags.split(";"):
            in_region = False
            region = []
            for file_line in file_lines:
                if re.match(rf"^\s*//\s*tag::{re.escape(tag)}\[\]\s*$", file_line):
                    in_region = True
                elif re.match(rf"^\s*//\s*end::{re.escape(tag)}\[\]\s*$", file_line):
                    in_region = False
                elif in_region and not TAG_DIRECTIVE_RE.match(file_line):
                    region.append(file_line)
            if not region:
                sys.exit(f"error: tag '{tag}' not found or empty in {source_file}")
            selected.extend(region)
    else:
        selected = [l for l in file_lines if not TAG_DIRECTIVE_RE.match(l)]

    if attrs.get("indent") is not None:
        target = int(attrs["indent"])
        indents = [len(l) - len(l.lstrip()) for l in selected if l.strip()]
        common = min(indents) if indents else 0
        selected = [(" " * target) + l[common:] if l.strip() else "" for l in selected]
    return selected


def normalize(lines):
    """Strip trailing whitespace and trailing blank lines for comparison."""
    result = [l.rstrip() for l in lines]
    while result and not result[-1]:
        result.pop()
    while result and not result[0]:
        result.pop(0)
    return result


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    adoc_path = Path(sys.argv[1])
    revision = sys.argv[2] if len(sys.argv) > 2 else "HEAD"

    repo_root = Path(subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"], cwd=adoc_path.parent, text=True).strip())
    rel_path = adoc_path.resolve().relative_to(repo_root)
    old_content = subprocess.check_output(
        ["git", "show", f"{revision}:{rel_path}"], cwd=repo_root, text=True)

    old_blocks = extract_source_blocks(old_content.splitlines())
    new_lines = adoc_path.read_text().splitlines()
    new_blocks_raw = extract_source_blocks(new_lines)

    examples_root = find_examples_root(adoc_path)
    new_blocks = []
    for body in new_blocks_raw:
        if body and all(INCLUDE_RE.match(line) for line in body):
            # A block may concatenate several include directives (e.g. two records
            # from separate files); resolve each and join in order, as Asciidoctor does.
            resolved = []
            for line in body:
                resolved.extend(resolve_include(line, examples_root))
            new_blocks.append(resolved)
        else:
            new_blocks.append(body)

    if len(old_blocks) != len(new_blocks):
        print(f"block count differs: {len(old_blocks)} in {revision}, {len(new_blocks)} in working tree")
        return 1

    drift = 0
    for index, (old, new) in enumerate(zip(old_blocks, new_blocks), start=1):
        old_n, new_n = normalize(old), normalize(new)
        if old_n != new_n:
            drift += 1
            print(f"--- block {index} differs ---")
            for diff_line in difflib.unified_diff(old_n, new_n, lineterm="",
                                                  fromfile=f"{revision}", tofile="resolved"):
                print(diff_line)
            print()
    if drift:
        print(f"{drift} of {len(new_blocks)} blocks differ")
        return 1
    print(f"all {len(new_blocks)} blocks match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
