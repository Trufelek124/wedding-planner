#!/usr/bin/env python3
"""Import a backlog batch (epics, stories, tasks) into infokuznia, once.

    scripts/ops/ik_seed.py docs/backlog/<batch>.json [--dry-run]

A batch is a JSON object: {"projectCode", "epics": [...], "stories": [...], "tasks": [...]}.
Every entry has a "key" of our own. A story names its epic by key ("epic"), a task
names its story ("story") and the tasks it waits for ("dependsOn"); keys may come
from this batch or from an earlier one.

Created ids are written to docs/infokuznia-ids.json after every call, so the
import can be rerun: an entry whose key is already in that file is skipped.

--dry-run checks every entry against the tracker's own tool schemas and the
references between entries, and creates nothing.
"""
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ik  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parents[2]
IDS_FILE = REPO / "docs" / "infokuznia-ids.json"

# batch field -> (tool, key of the object in the tool's answer, fields that are ours and not the tool's)
KINDS = {
    "epics": ("create_epic", "epic", ("key",)),
    "stories": ("create_story", "story", ("key", "epic")),
    "tasks": ("create_task", "task", ("key", "story", "dependsOn")),
}


def check(value, schema, path, problems):
    """A small JSON-schema check: the keywords the tracker's tool schemas use."""
    kind = schema.get("type")
    if kind == "object":
        if not isinstance(value, dict):
            return problems.append(f"{path}: expected an object")
        props = schema.get("properties", {})
        for name in schema.get("required", []):
            if name not in value:
                problems.append(f"{path}.{name}: missing")
        for name, item in value.items():
            if name in props:
                check(item, props[name], f"{path}.{name}", problems)
            elif schema.get("additionalProperties") is False:
                problems.append(f"{path}.{name}: not a field of this tool")
    elif kind == "array":
        if not isinstance(value, list):
            return problems.append(f"{path}: expected a list")
        if len(value) < schema.get("minItems", 0):
            problems.append(f"{path}: fewer than {schema['minItems']} items")
        if len(value) > schema.get("maxItems", len(value)):
            problems.append(f"{path}: more than {schema['maxItems']} items")
        for index, item in enumerate(value):
            check(item, schema.get("items", {}), f"{path}[{index}]", problems)
    elif kind == "string":
        if not isinstance(value, str):
            return problems.append(f"{path}: expected text")
        if len(value) < schema.get("minLength", 0):
            problems.append(f"{path}: shorter than {schema['minLength']} characters")
        if len(value) > schema.get("maxLength", len(value)):
            problems.append(f"{path}: {len(value)} characters, limit {schema['maxLength']}")
        if "enum" in schema and value not in schema["enum"]:
            problems.append(f"{path}: {value!r} is not one of {schema['enum']}")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            problems.append(f"{path}: {value!r} does not match {schema['pattern']}")
    elif kind == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            return problems.append(f"{path}: expected a whole number")
        if value < schema.get("minimum", value) or value > schema.get("maximum", value):
            problems.append(f"{path}: out of range")
    elif kind == "boolean" and not isinstance(value, bool):
        problems.append(f"{path}: expected true or false")


def arguments_for(kind, entry, project_code, ids):
    """The tool arguments for one batch entry; raises KeyError for an unknown reference."""
    _, _, ours = KINDS[kind]
    args = {name: value for name, value in entry.items() if name not in ours}
    if kind == "epics":
        args["projectCode"] = project_code
    elif kind == "stories":
        args["projectCode"] = project_code
        args["epicId"] = ids[entry["epic"]]["id"]
    else:
        args["storyId"] = ids[entry["story"]]["id"]
        if entry.get("dependsOn"):
            args["dependencyIds"] = [ids[key]["id"] for key in entry["dependsOn"]]
    return args


def main(argv):
    dry_run = "--dry-run" in argv
    files = [a for a in argv if not a.startswith("--")]
    if len(files) != 1:
        print(__doc__)
        return 2
    batch = json.loads(pathlib.Path(files[0]).read_text())
    project_code = batch["projectCode"]
    store = json.loads(IDS_FILE.read_text()) if IDS_FILE.is_file() else {"projectCode": project_code, "items": {}}
    if store["projectCode"] != project_code:
        raise SystemExit(f"{IDS_FILE.name} belongs to project {store['projectCode']}, the batch to {project_code}")
    ids = store["items"]

    schemas = {tool["name"]: tool["inputSchema"] for tool in ik.tools()}
    placeholder = {"id": "00000000-0000-0000-0000-000000000000"}
    problems, seen, planned = [], set(ids), dict(ids)
    for kind in ("epics", "stories", "tasks"):
        tool = KINDS[kind][0]
        for entry in batch.get(kind, []):
            key = entry.get("key")
            if not key:
                problems.append(f"{kind}: an entry without a key")
                continue
            if key in seen and key not in ids:
                problems.append(f"{key}: key used twice in the batch")
            seen.add(key)
            planned.setdefault(key, placeholder)
            try:
                args = arguments_for(kind, entry, project_code, planned)
            except KeyError as missing:
                problems.append(f"{key}: refers to {missing}, which is not defined before it")
                continue
            check(args, schemas[tool], key, problems)
    if problems:
        print("\n".join(problems))
        print(f"{len(problems)} problem(s); nothing created")
        return 2

    created = skipped = 0
    for kind in ("epics", "stories", "tasks"):
        tool, answer_key, _ = KINDS[kind]
        for entry in batch.get(kind, []):
            key = entry["key"]
            if key in ids:
                skipped += 1
                continue
            if dry_run:
                created += 1
                continue
            answer = ik.call(tool, arguments_for(kind, entry, project_code, ids))[answer_key]
            ids[key] = {"type": answer_key, "publicId": answer["publicId"], "id": answer["id"],
                        "title": entry.get("title") or entry.get("name")}
            IDS_FILE.write_text(json.dumps(store, ensure_ascii=False, indent=1) + "\n")
            created += 1
            print(f"{answer['publicId']:10} {key}")
    verb = "would create" if dry_run else "created"
    print(f"{verb} {created}, skipped {skipped} already imported")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except ik.IkError as error:
        print(json.dumps(error.payload, ensure_ascii=False, indent=1), file=sys.stderr)
        sys.exit(2)
