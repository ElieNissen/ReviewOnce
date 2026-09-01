#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root}"

blocked_files="$(git ls-files | grep -E '(^|/)\.env($|\.)|\.pem$|\.key$|\.keystore$|\.jks$|google-services\.json$' | grep -v '^\.env\.example$' || true)"
if [[ -n "${blocked_files}" ]]; then
  echo "Privacy check failed: sensitive file type is tracked." >&2
  echo "${blocked_files}" >&2
  exit 1
fi

secret_pattern='BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY|github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{20,}|(api[_-]?key|client[_-]?secret|password|access[_-]?token)[[:space:]]*[:=][[:space:]]*["'\"'][^"'\"']{8,}'
if git grep -n -I -E "${secret_pattern}" -- . ':!package-lock.json' ':!scripts/check-privacy.sh'; then
  echo "Privacy check failed: possible credential found." >&2
  exit 1
fi

echo "Privacy check passed."
