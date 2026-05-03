#!/usr/bin/env bash
# Wun installer.
#
# Two ways to use this:
#
#   1. From a local checkout:
#         ./install.sh
#
#   2. One-liner (requires the repo to be public on GitHub):
#         curl -fsSL https://raw.githubusercontent.com/Holy-Coders/wun/master/install.sh | bash
#
# What it does:
#   - ensures babashka is on PATH (offers `brew install` if missing on macOS)
#   - if invoked via curl-pipe-bash, clones the repo into ~/.wun
#   - symlinks bin/wun into one of:
#         /usr/local/bin/wun       (preferred, requires sudo or write perms)
#         $HOME/.local/bin/wun     (fallback; PATH-instructions printed if missing)
#
# Re-running is safe: the symlink target is overwritten in-place.

set -euo pipefail

REPO_URL="https://github.com/Holy-Coders/wun.git"
DEFAULT_CLONE_DIR="${WUN_HOME:-$HOME/.wun}"

color() { printf '\033[%sm%s\033[0m' "$1" "$2"; }
ok()    { printf '%s %s\n' "$(color '32' '✓')" "$1"; }
step()  { printf '%s %s\n' "$(color '36' '›')" "$1"; }
warn()  { printf '%s %s\n' "$(color '33' '!')" "$1" >&2; }
err()   { printf '%s %s\n' "$(color '31' '✗')" "$1" >&2; }

# ---------------------------------------------------------------------------
# Locate the repo. If this script lives inside a wun checkout (i.e. the
# user ran ./install.sh), use that. Otherwise (curl-pipe-bash) clone fresh.

repo_root_from_script() {
  local dir
  dir=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)
  if [[ -f "$dir/wun-server/deps.edn" ]]; then
    echo "$dir"
    return 0
  fi
  return 1
}

ensure_repo() {
  if WUN_ROOT=$(repo_root_from_script); then
    ok "using local checkout: $WUN_ROOT"
    return
  fi
  step "no local checkout; cloning $REPO_URL into $DEFAULT_CLONE_DIR"
  if ! command -v git >/dev/null 2>&1; then
    err "git not on PATH; install git or run install.sh from a clone"
    exit 1
  fi
  if [[ -d "$DEFAULT_CLONE_DIR/.git" ]]; then
    git -C "$DEFAULT_CLONE_DIR" pull --ff-only
  else
    git clone --depth=1 "$REPO_URL" "$DEFAULT_CLONE_DIR"
  fi
  WUN_ROOT="$DEFAULT_CLONE_DIR"
}

# ---------------------------------------------------------------------------
# babashka

ensure_bb() {
  if command -v bb >/dev/null 2>&1; then
    ok "babashka: $(bb --version)"
    return
  fi
  step "babashka not on PATH"
  if [[ "$OSTYPE" == "darwin"* ]] && command -v brew >/dev/null 2>&1; then
    read -p "install via 'brew install borkdude/brew/babashka'? [Y/n] " -r reply
    reply=${reply:-Y}
    if [[ $reply =~ ^[Yy]$ ]]; then
      brew install borkdude/brew/babashka
      ok "babashka installed: $(bb --version)"
      return
    fi
  fi
  err "babashka required. Install instructions: https://github.com/babashka/babashka#installation"
  exit 1
}

# ---------------------------------------------------------------------------
# Symlink bin/wun into a directory on PATH

choose_target() {
  # Prefer /usr/local/bin if writable; otherwise ~/.local/bin.
  if [[ -w /usr/local/bin ]]; then
    echo /usr/local/bin/wun
    return
  fi
  if [[ -d "$HOME/.local/bin" || ! -e "$HOME/.local/bin" ]]; then
    mkdir -p "$HOME/.local/bin"
    echo "$HOME/.local/bin/wun"
    return
  fi
  echo ""
}

install_symlink() {
  local target
  target=$(choose_target)
  if [[ -z "$target" ]]; then
    err "no writable bin dir found (tried /usr/local/bin and ~/.local/bin)"
    exit 1
  fi

  # Replace any existing symlink/file in place.
  ln -sfn "$WUN_ROOT/bin/wun" "$target"
  chmod +x "$WUN_ROOT/bin/wun" "$WUN_ROOT/cli/wun.bb"
  ok "symlinked $target -> $WUN_ROOT/bin/wun"

  # Tell the user if the bin dir isn't on PATH yet.
  case ":$PATH:" in
    *":$(dirname "$target"):"*) ;;
    *)
      warn "$(dirname "$target") is not on your PATH; add it with:"
      echo "    export PATH=\"$(dirname "$target"):\$PATH\""
      ;;
  esac
}

register_active_wun() {
  # Persist the canonical path of this checkout so the CLI, the MCP
  # server, and `wun new app --link` all agree on which Wun is the
  # editable one. Mirrors `wun link` (the CLI command) so a fresh
  # install is implicitly linked.
  local cfg_dir="${XDG_CONFIG_HOME:-$HOME/.config}/wun"
  local cfg_file="$cfg_dir/active.edn"
  mkdir -p "$cfg_dir"
  cat > "$cfg_file" <<EOF
{:root        "$WUN_ROOT"
 :linked-at   $(date +%s)
 :linked-from "$WUN_ROOT"
 :version     1}
EOF
  ok "registered active editable wun: $WUN_ROOT"
}

# ---------------------------------------------------------------------------

main() {
  step "installing wun"
  ensure_repo
  ensure_bb
  install_symlink
  register_active_wun
  echo
  ok "done. Try: $(color '1' 'wun doctor')"
}

main "$@"
