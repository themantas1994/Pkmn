#!/usr/bin/env bash
# Claude Code cloud sessions only (called from .claude/settings.json when CLAUDE_CODE_REMOTE=true).
# Finds a private config repo and runs its bootstrap. Two ways to provide it:
#   1. Attach the repo to the cloud environment, so it is cloned next to this one; or
#   2. set CLAUDE_CONFIG_REPO (owner/name) and CLAUDE_CONFIG_TOKEN (token for that repo only).
# Without either this prints one line and does nothing.
set -u
dest="$HOME/claude-config"
name="${CLAUDE_CONFIG_DIRNAME:-claude-config}"
if [ ! -e "$dest/cloud/bootstrap.sh" ]; then
  for d in "${CLAUDE_PROJECT_DIR:-$PWD}/../$name" "$PWD/$name" "$PWD/../$name" "/home/user/$name" "/workspace/$name"; do
    if [ -e "$d/cloud/bootstrap.sh" ]; then ln -sfn "$(cd "$d" && pwd)" "$dest"; break; fi
  done
fi
if [ -d "$dest/.git" ] || [ -e "$dest/.git" ]; then
  git -C "$dest" pull -q --ff-only 2>/dev/null || true
elif [ -n "${CLAUDE_CONFIG_REPO:-}" ] && [ -n "${CLAUDE_CONFIG_TOKEN:-}" ]; then
  git -c credential.helper= clone -q \
    "https://x-access-token:${CLAUDE_CONFIG_TOKEN}@github.com/${CLAUDE_CONFIG_REPO}.git" "$dest" 2>/dev/null \
    || echo "cloud-start: could not clone the private config repo."
fi
if [ -x "$dest/cloud/bootstrap.sh" ]; then
  "$dest/cloud/bootstrap.sh"
else
  echo "cloud-start: no private config repo found; using this repository's config only."
fi
exit 0
