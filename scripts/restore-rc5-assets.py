#!/usr/bin/env python3
"""Restore the owner's exact RC5 avatar/icon into ignored local build inputs.

Usage: python scripts/restore-rc5-assets.py /path/to/original-RC5.apk
No credentials, preferences or signing keys are extracted. Assets stay private.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def restore(apk: Path, root: Path) -> dict:
    prefix = 'assets/characters/evelyn/'
    pending = {}
    with zipfile.ZipFile(apk) as package:
        for item in package.infolist():
            if item.is_dir() or not item.filename.startswith(prefix):
                continue
            relative = PurePosixPath(item.filename).relative_to(prefix)
            if not relative.parts or any(part in ('..', '.') for part in relative.parts) or '\\' in str(relative):
                raise ValueError('Invalid character asset path')
            if item.file_size > 32 * 1024 * 1024:
                raise ValueError('Unexpectedly large character asset')
            pending[root / 'assets/characters/evelyn' / str(relative)] = package.read(item)
        icon = 'res/drawable/myraa_icon.png'
        if icon not in package.namelist():
            raise ValueError('The original MYRAA PNG icon was not found')
        pending[root / 'assets/branding/myraa_icon.png'] = package.read(icon)
    if not (root / 'assets/characters/evelyn/model.pmx') in pending or not (root / 'assets/characters/evelyn/textures.json') in pending:
        raise ValueError('The RC5 character model and texture map are required')
    if sum(len(value) for value in pending.values()) > 64 * 1024 * 1024:
        raise ValueError('Asset package exceeds the expected size bound')
    # Check every target first. Never silently overwrite the owner's other assets.
    for target, data in pending.items():
        if target.exists() and target.read_bytes() != data:
            raise FileExistsError(f'Existing different asset preserved: {target}')
    hashes = {}
    for target, data in pending.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        hashes[str(target.relative_to(root))] = hashlib.sha256(data).hexdigest()
    return {'source_apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'files': hashes,
            'character_files': len(hashes) - 1, 'icon_restored': True,
            'signing_key_recovered': False, 'distribution': 'private_owner_build_only'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    report = restore(args.apk, root)
    output = root / 'artifacts/rc5-restoration/assets-restored.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Restored {report["character_files"]} character files and the original icon. Private local build inputs only.')
    print(f'Original APK SHA-256: {report["source_apk_sha256"]}')
    print('Signing keys cannot be recovered from an APK; the original signing identity is still required for RC5 updates.')


if __name__ == '__main__':
    main()
