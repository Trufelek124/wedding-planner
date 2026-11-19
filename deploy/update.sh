#!/usr/bin/env bash
# Takes the newest published image, if there is one, and starts the planner on it.
# Run it every few minutes on the server (launchd, cron or a systemd timer), from anywhere:
# it works in the directory it lies in, which is the one with compose.yaml and .env.
#
#   IMAGE_TAG=latest in .env   the planner follows main: every commit that is published goes live.
#   IMAGE_TAG=<a commit>       the planner stays on that commit, whatever is published (a rollback).
#
# It says something only when it did something or could not: a quiet run changed nothing.
# docs/deployment.md, "The server updates itself", has an example of a job that runs it.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

running() { docker compose images --quiet app 2>/dev/null | head -n 1; }

before=$(running)
# Nothing new, the registry not reachable, the machine just woken: all of these are a later run's to
# try again, and none of them is a reason to touch what is running.
if ! docker compose --progress quiet pull app; then
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) the image could not be asked for; the planner runs on as it is" >&2
  exit 0
fi
# Starts the new image when there is one, and what is not running; leaves alone what is up to date.
docker compose --progress quiet up --detach --no-build app
after=$(running)
if [ "$before" != "$after" ] && [ -n "$after" ]; then
  commit=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$after" 2>/dev/null || true)
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) the planner now runs on commit ${commit:-unknown} (image ${after:0:12})"
fi
