#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CACHE_ROOT="${HERMES_UI_CACHE:-$HOME/.cache/herdroid-hermes-ui}"
UPSTREAM_DIR="$CACHE_ROOT/hermes-agent"
OUTPUT_DIR="$ROOT_DIR/app/src/main/assets/hermes"
BOOTSTRAP="$ROOT_DIR/app/src/main/assets/herdroid/herdroid-bootstrap.js"
HERMES_UI_REF="${HERMES_UI_REF:-b154046e4efb26c1c62d91f3ad52534cfa523cee}"

log() { printf '\n\033[1;34m[HerDroid UI]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[HerDroid UI]\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die "git is required"
command -v node >/dev/null 2>&1 || die "Node.js 22.22+ is required. In Termux: pkg install nodejs"
command -v npm >/dev/null 2>&1 || die "npm is required"

node_major="$(node -p 'process.versions.node.split(".")[0]')"
node_minor="$(node -p 'process.versions.node.split(".")[1]')"
if (( node_major < 22 || (node_major == 22 && node_minor < 22) )); then
    die "Hermes Desktop requires Node.js >=22.22.0. Installed: $(node --version)"
fi

mkdir -p "$CACHE_ROOT"

if [[ ! -d "$UPSTREAM_DIR/.git" ]]; then
    log "Cloning NousResearch/hermes-agent renderer source"
    git clone --filter=blob:none https://github.com/NousResearch/hermes-agent.git "$UPSTREAM_DIR"
fi

log "Syncing Hermes UI ref $HERMES_UI_REF"
git -C "$UPSTREAM_DIR" fetch --depth=1 origin "$HERMES_UI_REF"
git -C "$UPSTREAM_DIR" checkout --detach FETCH_HEAD

log "Installing renderer dependencies without native install scripts"
(
    cd "$UPSTREAM_DIR"
    npm install --workspace apps/desktop --ignore-scripts --engine-strict=false --no-audit --no-fund
)

log "Building the official Hermes Vite renderer"
(
    cd "$UPSTREAM_DIR/apps/desktop"
    node scripts/write-build-stamp.mjs
    ../../node_modules/.bin/vite build
)

[[ -f "$UPSTREAM_DIR/apps/desktop/dist/index.html" ]] \
    || die "Hermes Vite build completed without dist/index.html"
[[ -f "$BOOTSTRAP" ]] || die "Missing Android renderer bootstrap: $BOOTSTRAP"

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
cp -a "$UPSTREAM_DIR/apps/desktop/dist/." "$OUTPUT_DIR/"
cp -f "$BOOTSTRAP" "$OUTPUT_DIR/herdroid-bootstrap.js"

# Inject the Android bootstrap before Vite's renderer module executes.
# Activation stays gated until the full window.hermesDesktop compatibility
# layer reaches boot parity.
node - "$OUTPUT_DIR/index.html" <<'NODE'
const fs = require('fs')
const file = process.argv[2]
let text = fs.readFileSync(file, 'utf8')
const tag = '<script src="./herdroid-bootstrap.js"></script>'
if (!text.includes(tag)) {
  if (!text.includes('<head>')) throw new Error('Hermes renderer index has no <head> tag')
  text = text.replace('<head>', `<head>\n    ${tag}`)
  fs.writeFileSync(file, text)
}
NODE

printf '%s\n' "$HERMES_UI_REF" > "$OUTPUT_DIR/.upstream-ref"
rm -f "$OUTPUT_DIR/herdroid-ready.json"

log "Hermes renderer packaged at $OUTPUT_DIR"
printf 'Upstream ref: %s\n' "$HERMES_UI_REF"
printf 'Note: renderer activation stays gated until the Android hermesDesktop bridge reaches boot parity.\n'
