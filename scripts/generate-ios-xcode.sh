#!/usr/bin/env bash
# Generate SmileMultifab.xcodeproj dari project.yml (Mac + XcodeGen).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$ROOT/iosApp"
cd "$IOS"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "Install XcodeGen dulu: brew install xcodegen"
  exit 1
fi

xcodegen generate
echo "OK → $IOS/SmileMultifab.xcodeproj"
echo "Buka: open \"$IOS/SmileMultifab.xcodeproj\""
