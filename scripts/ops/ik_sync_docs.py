#!/usr/bin/env python3
"""Mirror docs/ into infokuznia documents. The repository is the source.

    scripts/ops/ik_sync_docs.py [--dry-run] [--only <path>]

docs/infokuznia-docs.json says what is mirrored:

    "documents"  one infokuznia document per file: {"path", "type", "link"?, "title"?}
                 "link" is a key from docs/infokuznia-ids.json (an epic, story or
                 task); without it the document belongs to the project.
                 The title defaults to the file's first heading.
    "decisions"  {"path", "type"}: a file whose "## D-nnn — title" sections
                 become one document each.
    "state"      written by this script: document id and content hash per source.

A source whose hash is unchanged is not written again, so a second run reports
0 created, 0 updated. Each document starts with a line naming its source path
and the commit that last changed it; a file with uncommitted changes is refused.
Commit docs/infokuznia-docs.json after a run that wrote something.

Never edit a document in infokuznia by hand: edit the file and run this again.
"""
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ik  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parents[2]
MAP_FILE = REPO / "docs" / "infokuznia-docs.json"
IDS_FILE = REPO / "docs" / "infokuznia-ids.json"
LINK_TYPES = {"epic": "EPIC", "story": "STORY", "task": "TASK"}
DECISION = re.compile(r"^## (D-\d{3,}) — (.+)$", re.MULTILINE)
TITLE_LIMIT, CONTENT_LIMIT = 240, 200_000


def git(*args):
    return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True, check=True).stdout.strip()


def first_heading(text, fallback):
    match = re.search(r"^# (.+)$", text, re.MULTILINE)
    return match.group(1).strip() if match else fallback


def sources(config, ids):
    """Yield one dict per document to mirror: key, path, type, title, body, link."""
    for entry in config.get("documents", []):
        text = (REPO / entry["path"]).read_text()
        link = None
        if entry.get("link"):
            target = ids[entry["link"]]
            link = (LINK_TYPES[target["type"]], target["id"])
        yield {"key": entry["path"], "path": entry["path"], "type": entry["type"],
               "title": entry.get("title") or first_heading(text, entry["path"]), "body": text, "link": link}
    decisions = config.get("decisions")
    if decisions and (REPO / decisions["path"]).is_file():
        text = (REPO / decisions["path"]).read_text()
        marks = list(DECISION.finditer(text))
        for index, mark in enumerate(marks):
            end = marks[index + 1].start() if index + 1 < len(marks) else len(text)
            number, title = mark.group(1), mark.group(2).strip()
            body = f"# {number} — {title}\n\n{text[mark.end():end].strip()}\n"
            yield {"key": f"{decisions['path']}#{number}", "path": decisions["path"], "type": decisions["type"],
                   "title": f"{number} — {title}", "body": body, "link": None}


def rendered(source, commit, today):
    """The document as stored: the source line goes under the first heading."""
    note = (f"*Source: `{source['key']}` in `{REPO.name}` @ `{commit}`. "
            f"Mirrored {today} by ik_sync_docs.py — edit the file, not this document.*")
    lines = source["body"].split("\n")
    at = next((i for i, line in enumerate(lines) if line.startswith("# ")), None)
    if at is None:
        return note + "\n\n" + source["body"]
    return "\n".join(lines[:at + 1] + ["", note] + lines[at + 1:])


def main(argv):
    dry_run = "--dry-run" in argv
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    config = json.loads(MAP_FILE.read_text())
    ids = json.loads(IDS_FILE.read_text())
    state = config.setdefault("state", {})
    today = datetime.date.today().isoformat()
    created = updated = unchanged = 0

    listed = {entry["path"] for entry in config.get("documents", [])}
    listed.add((config.get("decisions") or {}).get("path"))
    for path in sorted(REPO.glob("docs/**/*.md")):
        relative = path.relative_to(REPO).as_posix()
        if relative not in listed and not any(relative.startswith(p) for p in config.get("notMirrored", [])):
            print(f"note: {relative} is not in {MAP_FILE.name} and is not mirrored")

    for source in sources(config, ids["items"]):
        if only and source["path"] != only:
            continue
        if git("status", "--porcelain", "--", source["path"]):
            raise SystemExit(f"{source['path']} has uncommitted changes: commit it, then sync")
        title = source["title"][:TITLE_LIMIT]
        digest = hashlib.sha256((source["type"] + "\n" + title + "\n" + source["body"]).encode()).hexdigest()
        known = state.get(source["key"])
        if known and known["sha256"] == digest:
            unchanged += 1
            continue
        commit = git("log", "-1", "--format=%h", "--", source["path"])
        content = rendered(source, commit, today)
        if len(content) > CONTENT_LIMIT:
            raise SystemExit(f"{source['key']}: {len(content)} characters, the tracker's limit is {CONTENT_LIMIT}")
        if dry_run:
            print(f"would {'update' if known else 'create'} {source['key']}")
            created, updated = created + (not known), updated + bool(known)
            continue
        if known:
            ik.call("update_document", {"id": known["id"], "title": title, "content": content,
                                        "changeComment": f"Mirror of {source['key']} @ {commit}"})
            public_id, doc_id = known["publicId"], known["id"]
            updated += 1
        else:
            args = {"projectCode": ids["projectCode"], "type": source["type"], "title": title, "status": "ACTIVE",
                    "content": content, "changeComment": f"Mirror of {source['key']} @ {commit}"}
            if source["link"]:
                args["linkType"], args["linkId"] = source["link"]
            document = ik.call("create_document", args)["document"]
            public_id, doc_id = document["publicId"], document["id"]
            created += 1
        state[source["key"]] = {"publicId": public_id, "id": doc_id, "sha256": digest, "commit": commit}
        MAP_FILE.write_text(json.dumps(config, ensure_ascii=False, indent=1) + "\n")
        print(f"{public_id:10} {source['key']}")
    print(f"{'would create' if dry_run else 'created'} {created}, "
          f"{'would update' if dry_run else 'updated'} {updated}, unchanged {unchanged}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except ik.IkError as error:
        print(json.dumps(error.payload, ensure_ascii=False, indent=1), file=sys.stderr)
        sys.exit(2)
