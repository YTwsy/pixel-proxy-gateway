#!/usr/bin/env sh
set -eu

GOST_TAG=${GOST_TAG:-v3.2.6}
EXPECTED_GOST_COMMIT=${EXPECTED_GOST_COMMIT:-340ba32ef0bebc7293908007cc423dd5f33dd88c}
export LC_ALL=C
export LANG=C
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC_DIR=${SRC_DIR:-$ROOT/third_party/gost-src}
OUT_ASSET_DIR="$ROOT/app/src/main/assets/gost/android-arm64"
OUT_LIB_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
OUT_BIN="$OUT_LIB_DIR/libgost.so"
OUT_TAG="$OUT_ASSET_DIR/gost.tag"
OUT_COMMIT="$OUT_ASSET_DIR/gost.commit"
OUT_SHA="$OUT_ASSET_DIR/gost.sha256"
STALE_ASSET_BIN="$OUT_ASSET_DIR/gost"

if ! command -v go >/dev/null 2>&1; then
  echo "go is required to build GOST from source." >&2
  echo "Install Go, then rerun this script." >&2
  exit 127
fi

mkdir -p "$ROOT/third_party" "$OUT_ASSET_DIR" "$OUT_LIB_DIR"

if [ ! -d "$SRC_DIR/.git" ]; then
  git clone https://github.com/go-gost/gost.git "$SRC_DIR"
fi

cd "$SRC_DIR"
git fetch --tags --force
RESOLVED_GOST_COMMIT=$(git rev-list -n 1 "$GOST_TAG")
if [ "$RESOLVED_GOST_COMMIT" != "$EXPECTED_GOST_COMMIT" ]; then
  echo "Refusing to build unexpected GOST source." >&2
  echo "  tag=$GOST_TAG" >&2
  echo "  expected_commit=$EXPECTED_GOST_COMMIT" >&2
  echo "  resolved_commit=$RESOLVED_GOST_COMMIT" >&2
  exit 1
fi
git -c advice.detachedHead=false checkout --detach "$EXPECTED_GOST_COMMIT"
if [ -n "$(git status --porcelain)" ]; then
  echo "GOST source checkout is dirty: $SRC_DIR" >&2
  git status --short >&2
  exit 1
fi
GOST_COMMIT=$(git rev-parse HEAD)

cd "$SRC_DIR/cmd/gost"
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build \
  -trimpath \
  -ldflags="-s -w" \
  -o "$OUT_BIN" .

chmod 0755 "$OUT_BIN"
if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$OUT_BIN" | awk '{print $1}' > "$OUT_SHA"
else
  sha256sum "$OUT_BIN" | awk '{print $1}' > "$OUT_SHA"
fi
printf '%s\n' "$GOST_TAG" > "$OUT_TAG"
printf '%s\n' "$GOST_COMMIT" > "$OUT_COMMIT"
rm -f "$STALE_ASSET_BIN"

if command -v file >/dev/null 2>&1; then
  file "$OUT_BIN"
fi
echo "Built: $OUT_BIN"
echo "Tag: $GOST_TAG"
echo "Commit: $GOST_COMMIT"
echo "SHA256: $(cat "$OUT_SHA")"
