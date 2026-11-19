#!/usr/bin/env bash
# Publishes the planner's image: the checks, a build for the server's processor, and the push.
# The way by hand: the owner runs it on the Mac. Usually the image publishes itself, at every push to
# main, through .github/workflows/publish.yml (docs/decisions.md, D-010).
#
#   scripts/publish-image.sh               publish the commit that main is on
#   scripts/publish-image.sh --no-push     everything but the push, to see that it would work
#   scripts/publish-image.sh --full-gate   run the whole gate first, the Android part too
#
# What it insists on, so that the commit named in the image can always be found again:
# the checkout is on main, has no changes of its own, and is what the repository's main is.
#
# Settings, all optional:
#   IMAGE               the image's name without a tag. Default: ghcr.io/<owner>/<repository>,
#                       worked out from the address of the remote "origin".
#   PLATFORM            what the image is built for. Default: linux/arm64, the server's.
#   PUBLISH_BRANCH      the branch that is published. Default: main.
#   DOCKER_BUILD_FLAGS  more flags for "docker build", for a machine that needs them.
#
# Before the first run, once:  docker login <the registry>
# Docker keeps the token; this script never asks for one, stores one or prints one.
#
# Needs: git, Docker with its daemon running, and what the gate needs (JDK 25, Node 22).
# Exit codes: 0 published (or built, with --no-push), 1 refused or failed, 2 wrong arguments.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PLATFORM=${PLATFORM:-linux/arm64}
PUBLISH_BRANCH=${PUBLISH_BRANCH:-main}
PUSH=yes
GATE_PARTS="backend web"

for argument in "$@"; do
  case "$argument" in
    --no-push) PUSH=no ;;
    --full-gate) GATE_PARTS="" ;;
    -h | --help)
      sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown argument: $argument (see --help)" >&2
      exit 2
      ;;
  esac
done

refuse() {
  echo "Not published: $*" >&2
  exit 1
}

cd "$ROOT"

# ---- what is being published ---------------------------------------------------------

branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = "$PUBLISH_BRANCH" ] || refuse "this checkout is on \"$branch\"; only \"$PUBLISH_BRANCH\" is published."
[ -z "$(git status --porcelain)" ] || refuse "this checkout has changes that are not committed."
git fetch --quiet origin "$PUBLISH_BRANCH" || refuse "the repository could not be reached to compare with its $PUBLISH_BRANCH."
commit=$(git rev-parse HEAD)
[ "$commit" = "$(git rev-parse FETCH_HEAD)" ] ||
  refuse "this checkout is not what the repository's $PUBLISH_BRANCH is. Pull or push first."

origin=$(git remote get-url origin)
if [ -z "${IMAGE:-}" ]; then
  # https://github.com/Owner/Name.git, git@github.com:Owner/Name.git and the like -> ghcr.io/owner/name
  path=$(printf '%s' "$origin" | sed -n -E 's#^(https://|ssh://git@|git@)github\.com[:/]+([^/]+/[^/]+)$#\2#p' | sed -E 's#\.git$##')
  [ -n "$path" ] || refuse "the image's name cannot be worked out from \"origin\". Give it: IMAGE=registry/owner/name $0"
  IMAGE="ghcr.io/$(printf '%s' "$path" | tr '[:upper:]' '[:lower:]')"
fi
# A colon may stand in the registry's part (a port), never in the name itself.
case "${IMAGE##*/}" in
  *:* | *@*) refuse "IMAGE is the name without a tag: \"$IMAGE\" has one." ;;
esac

docker info >/dev/null 2>&1 || refuse "Docker is not running."

echo "Publishing $commit"
echo "       as $IMAGE:$commit and $IMAGE:latest, for $PLATFORM"

# ---- the checks ------------------------------------------------------------------------

# shellcheck disable=SC2086  # the parts are separate words on purpose
"$ROOT/scripts/gate.sh" $GATE_PARTS || refuse "the checks did not pass."
# The checks build things inside the checkout; none of that may be something git would publish.
[ -z "$(git status --porcelain)" ] || refuse "the checks left changes in the checkout."

# ---- the image -------------------------------------------------------------------------

labels=(
  --label "org.opencontainers.image.created=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
)
case "$origin" in
  https://*) labels+=(--label "org.opencontainers.image.source=${origin%.git}") ;;
esac

# shellcheck disable=SC2086  # DOCKER_BUILD_FLAGS holds separate flags on purpose
docker build \
  --platform "$PLATFORM" \
  --build-arg "REVISION=$commit" \
  "${labels[@]}" \
  --tag "$IMAGE:$commit" \
  --tag "$IMAGE:latest" \
  ${DOCKER_BUILD_FLAGS:-} \
  "$ROOT"

# Never push an image that does not say which commit it is, or that is for another machine.
labelled=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$IMAGE:$commit")
[ "$labelled" = "$commit" ] || refuse "the image built says it is commit \"$labelled\", not $commit."
built_for=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$IMAGE:$commit")
[ "$built_for" = "$PLATFORM" ] || refuse "the image built is for $built_for, not for $PLATFORM."

if [ "$PUSH" = no ]; then
  echo "Built and not pushed (--no-push): $IMAGE:$commit"
  exit 0
fi

# ---- the push --------------------------------------------------------------------------

for tag in "$commit" latest; do
  docker push "$IMAGE:$tag" ||
    refuse "\"$IMAGE:$tag\" was not accepted. If the registry asked who you are: docker login ${IMAGE%%/*}"
done

echo "Published: $IMAGE:$commit"
echo "      and: $IMAGE:latest"
