#!/usr/bin/env bash
# The gate: every check of every part. A change is ready only when this exits 0.
#
#   scripts/gate.sh                  all parts: backend, web, android
#   scripts/gate.sh backend web      only the parts named
#
# Stops at the first failing step, prints the end of that step's output and exits 1.
# Needs JDK 25, Node 22 and, for the Android part, ANDROID_HOME (see CLAUDE.md).
set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LOGS=$(mktemp -d "${TMPDIR:-/tmp}/gate.XXXXXX")
SUMMARY=()

# step <part> <name> <command...>: runs one check quietly; on failure shows why and ends the gate.
step() {
  local part=$1 name=$2 log
  shift 2
  log="$LOGS/$part-${name// /-}.log"
  printf '%-8s %s ... ' "$part" "$name"
  if (cd "$ROOT/$part" && "$@") >"$log" 2>&1; then
    echo ok
  else
    echo FAILED
    echo "----- last lines of: $* (full output: $log)"
    grep -v '^Picked up JAVA_TOOL_OPTIONS' "$log" | tail -40
    echo "gate: FAILED at $part / $name"
    exit 1
  fi
}

# tests_in <glob of JUnit XML files>: how many tests those result files hold.
tests_in() {
  python3 - "$@" <<'PY'
import glob, re, sys
total = 0
for pattern in sys.argv[1:]:
    for path in glob.glob(pattern):
        match = re.search(r'<testsuite[^>]*\btests="(\d+)"', open(path, encoding="utf-8").read())
        total += int(match.group(1)) if match else 0
print(total)
PY
}

backend() {
  step backend "tests" ./gradlew test
  SUMMARY+=("backend  $(tests_in "$ROOT/backend/build/test-results/test/*.xml") tests")
}

web() {
  if [ ! -d "$ROOT/web/node_modules" ] || [ "$ROOT/web/package-lock.json" -nt "$ROOT/web/node_modules/.package-lock.json" ]; then
    step web "install" npm ci --no-fund --no-audit
  fi
  step web "api types" npm run check:api
  step web "lint" npm run lint
  rm -f "$ROOT/web/node_modules/.tmp/gate-junit.xml"
  step web "tests" npm test -- --reporter=default --reporter=junit --outputFile.junit=node_modules/.tmp/gate-junit.xml
  step web "build" npm run build
  SUMMARY+=("web      $(tests_in "$ROOT/web/node_modules/.tmp/gate-junit.xml") tests, api types, lint, build")
}

android() {
  if [ -z "${ANDROID_HOME:-}" ] && [ ! -f "$ROOT/android/local.properties" ]; then
    echo "android  FAILED: ANDROID_HOME is not set and android/local.properties does not exist"
    echo "gate: FAILED at android / setup"
    exit 1
  fi
  step android "lint" ./gradlew lintDebug
  step android "tests" ./gradlew testDebugUnitTest
  step android "debug build" ./gradlew assembleDebug
  SUMMARY+=("android  $(tests_in "$ROOT/android/app/build/test-results/testDebugUnitTest/*.xml") tests, lint, debug build")
}

if [ $# -eq 0 ]; then
  set -- backend web android
fi
for part in "$@"; do
  case "$part" in
    backend | web | android) "$part" ;;
    *)
      echo "unknown part '$part': use backend, web or android"
      exit 2
      ;;
  esac
done

echo
printf '%s\n' "${SUMMARY[@]}"
echo "gate: PASSED"
rm -rf "$LOGS"
