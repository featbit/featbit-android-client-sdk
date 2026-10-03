#!/usr/bin/env bash
# Keep orchestration in Python; compatible with macOS Bash 3.2.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec "${PYTHON:-python3}" -u -B "$script_dir/run_live_acceptance.py" "$@"
