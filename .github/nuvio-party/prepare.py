#!/usr/bin/env python3
"""Adatta il build al fork "Nuvio Party" (solo in CI, non va committato il risultato).

- stesso pacchetto di Nuvio ufficiale: lo sostituisce (una sola app), aggiornamenti in-app dal fork
- nome app "Nuvio Party"
- aggiornamenti in-app dalle release del fork
- versione della build automatica

Uso: .github/nuvio-party/prepare.py <versionName> <versionCode> <owner/repo>
"""
import re
import sys
from pathlib import Path

version_name, version_code, repo = sys.argv[1], sys.argv[2], sys.argv[3]


def patch(path: str, pattern: str, replacement: str, count: int = 1) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    new_text, found = re.subn(pattern, replacement, text, count=count, flags=re.MULTILINE)
    if found == 0:
        sys.exit(f"::error::Modello non trovato in {path}: {pattern!r} (Nuvio ha cambiato il file?)")
    file.write_text(new_text, encoding="utf-8")


patch("iosApp/Configuration/Version.xcconfig", r"^CURRENT_PROJECT_VERSION=\d+$", f"CURRENT_PROJECT_VERSION={version_code}")
patch("iosApp/Configuration/Version.xcconfig", r"^MARKETING_VERSION=.*$", f"MARKETING_VERSION={version_name}")
patch(
    "composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/AppUpdaterRepository.kt",
    r"https://api\.github\.com/repos/NuvioMedia/NuvioMobile/",
    f"https://api.github.com/repos/{repo}/",
)
for strings in sorted(Path("composeApp/src/androidMain/res").glob("values*/strings.xml")):
    text = strings.read_text(encoding="utf-8")
    if 'name="app_name"' in text:
        patch(str(strings), r'<string name="app_name">[^<]*</string>', '<string name="app_name">Nuvio Party</string>')

print(f"Nuvio Party {version_name} ({version_code}) → aggiornamenti da {repo}")
