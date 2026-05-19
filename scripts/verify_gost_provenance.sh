#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC_DIR=${SRC_DIR:-$ROOT/third_party/gost-src}
GOST_TAG=${GOST_TAG:-v3.2.6}
EXPECTED_GOST_COMMIT=${EXPECTED_GOST_COMMIT:-340ba32ef0bebc7293908007cc423dd5f33dd88c}
export LC_ALL=C
export LANG=C
BIN=${BIN:-$ROOT/app/src/main/jniLibs/arm64-v8a/libgost.so}
ASSET_DIR=${ASSET_DIR:-$ROOT/app/src/main/assets/gost/android-arm64}
TAG_FILE="$ASSET_DIR/gost.tag"
COMMIT_FILE="$ASSET_DIR/gost.commit"
SHA_FILE="$ASSET_DIR/gost.sha256"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "$1 is required." >&2
    exit 2
  fi
}

sha256_file() {
  file=$1
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{ print $1 }'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{ print $1 }'
  else
    echo "shasum or sha256sum is required." >&2
    exit 2
  fi
}

need git
need awk

if [ ! -d "$SRC_DIR/.git" ]; then
  echo "GOST source checkout not found: $SRC_DIR" >&2
  echo "Run tools/build-gost-android-arm64.sh to clone and build the pinned source." >&2
  exit 1
fi
for file in "$BIN" "$TAG_FILE" "$COMMIT_FILE" "$SHA_FILE"; do
  if [ ! -f "$file" ]; then
    echo "Missing GOST provenance file: $file" >&2
    exit 1
  fi
done

resolved_commit=$(git -C "$SRC_DIR" rev-list -n 1 "$GOST_TAG")
if [ "$resolved_commit" != "$EXPECTED_GOST_COMMIT" ]; then
  echo "GOST tag resolves to an unexpected commit." >&2
  echo "  tag=$GOST_TAG" >&2
  echo "  expected_commit=$EXPECTED_GOST_COMMIT" >&2
  echo "  resolved_commit=$resolved_commit" >&2
  exit 1
fi

head_commit=$(git -C "$SRC_DIR" rev-parse HEAD)
if [ "$head_commit" != "$EXPECTED_GOST_COMMIT" ]; then
  echo "GOST source checkout is not at the pinned commit." >&2
  echo "  expected_commit=$EXPECTED_GOST_COMMIT" >&2
  echo "  head_commit=$head_commit" >&2
  exit 1
fi

dirty=$(git -C "$SRC_DIR" status --porcelain)
if [ -n "$dirty" ]; then
  echo "GOST source checkout is dirty: $SRC_DIR" >&2
  printf '%s\n' "$dirty" >&2
  exit 1
fi

asset_tag=$(tr -d '\r\n' < "$TAG_FILE")
asset_commit=$(tr -d '\r\n' < "$COMMIT_FILE")
asset_sha=$(awk '{ print $1 }' "$SHA_FILE")
actual_sha=$(sha256_file "$BIN")

if [ "$asset_tag" != "$GOST_TAG" ]; then
  echo "GOST tag metadata mismatch: expected=$GOST_TAG actual=$asset_tag" >&2
  exit 1
fi
if [ "$asset_commit" != "$EXPECTED_GOST_COMMIT" ]; then
  echo "GOST commit metadata mismatch: expected=$EXPECTED_GOST_COMMIT actual=$asset_commit" >&2
  exit 1
fi
if [ "$asset_sha" != "$actual_sha" ]; then
  echo "GOST binary SHA mismatch: expected=$asset_sha actual=$actual_sha" >&2
  exit 1
fi
if [ "${#actual_sha}" -ne 64 ]; then
  echo "GOST SHA256 is malformed: $actual_sha" >&2
  exit 1
fi
case "$actual_sha" in
  *[!0123456789abcdef]*)
    echo "GOST SHA256 is malformed: $actual_sha" >&2
    exit 1
    ;;
esac

echo "GOST provenance verification passed:"
echo "  source=$SRC_DIR"
echo "  tag=$GOST_TAG"
echo "  commit=$EXPECTED_GOST_COMMIT"
echo "  binary=$BIN"
echo "  sha256=$actual_sha"
