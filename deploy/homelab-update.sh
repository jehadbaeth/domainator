#!/usr/bin/env bash
# Pull-based continuous deployment for a home server.
#
# Run from cron every few minutes. When main has a new commit and every CI check on that commit has passed,
# it fast-forwards the checkout and rebuilds the containers. Nothing on GitHub needs access to the server.
#
#   */5 * * * * $HOME/deployments/domainator/deploy/homelab-update.sh >> $HOME/deployments/domainator-deploy.log 2>&1
#
# Pass --force to rebuild the current commit without waiting for a new one.
set -euo pipefail

REPO_DIR="${REPO_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
REPO="${REPO:-jehadbaeth/domainator}"
BRANCH="${BRANCH:-main}"
FORCE="${1:-}"

exec 9>"/tmp/domainator-deploy.lock"
flock -n 9 || exit 0   # a previous run is still building

cd "$REPO_DIR"
git fetch -q origin "$BRANCH"
remote=$(git rev-parse "origin/$BRANCH")
current=$(git rev-parse HEAD)
log() { echo "$(date -Is) $*"; }

if [[ "$remote" == "$current" && "$FORCE" != "--force" ]]; then
  exit 0
fi

if [[ "$remote" != "$current" ]]; then
  # only deploy commits whose CI succeeded (public repo, so no token needed)
  state=$(curl -fsS -H 'Accept: application/vnd.github+json' \
      "https://api.github.com/repos/$REPO/commits/$remote/check-runs?per_page=100" | python3 -c '
import json, sys
runs = json.load(sys.stdin).get("check_runs", [])
if not runs or any(r["status"] != "completed" for r in runs):
    print("pending")
elif all(r["conclusion"] in ("success", "skipped", "neutral") for r in runs):
    print("success")
else:
    print("failure")')
  case "$state" in
    success) ;;
    pending) exit 0 ;;   # CI still running, try again next time
    *)
      if [[ "$(cat .deploy-rejected 2>/dev/null)" != "$remote" ]]; then
        log "CI failed for ${remote:0:7}, not deploying"
        echo "$remote" > .deploy-rejected
      fi
      exit 0 ;;
  esac
  git merge -q --ff-only "origin/$BRANCH"
fi

log "deploying ${remote:0:7}: $(git log -1 --format=%s)"
docker compose up -d --build --remove-orphans
docker image prune -f > /dev/null
log "deployed ${remote:0:7}"
