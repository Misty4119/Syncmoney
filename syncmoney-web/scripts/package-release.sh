#!/usr/bin/env bash
set -euo pipefail

rm -rf release-package
mkdir -p release-package/syncmoney-web

while IFS= read -r -d '' file; do
  case "$file" in
    .git/*|.github/*|.vscode/*|.gitignore|dist/*|node_modules/*|coverage/*|test-results/*|playwright-report/*|syncmoney-web.tar.gz|syncmoney-web.sha256)
      continue
      ;;
  esac
  destination="release-package/syncmoney-web/$file"
  mkdir -p "$(dirname "$destination")"
  cp -- "$file" "$destination"
done < <(git ls-files -z)

tar --sort=name \
  --mtime='UTC 2020-01-01' \
  --owner=0 \
  --group=0 \
  --numeric-owner \
  -czf syncmoney-web.tar.gz \
  -C release-package syncmoney-web
