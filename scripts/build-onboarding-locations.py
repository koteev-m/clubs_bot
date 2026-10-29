#!/usr/bin/env python3
"""Build the offline GeoNames derivative from explicitly downloaded public inputs.

Usage: python3 scripts/build-onboarding-locations.py INPUT_DIRECTORY OUTPUT_DIRECTORY
No networking, user data, or implicit refresh. Review the resulting snapshot diff.
"""
import gzip
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def build(source: Path, target: Path) -> None:
    regions = {}
    for line in (source / 'admin1CodesASCII.txt').read_text().splitlines():
        fields = line.split('\t')
        regions[fields[0]] = fields[1]
    rows = []
    with zipfile.ZipFile(source / 'cities15000.zip') as archive:
        for line in archive.read('cities15000.txt').decode('utf-8').splitlines():
            fields = line.split('\t')
            identifier, name, ascii_name, aliases = fields[:4]
            country, admin = fields[8], fields[10]
            zone = fields[17]
            if not country or not zone:
                continue
            region = regions.get(country + '.' + admin, '')
            names = sorted(set([name, ascii_name] + aliases.split(',')) - {''})
            row = [identifier, country, name, region, zone, '|'.join(names)]
            assert all('\t' not in value and '\n' not in value for value in row)
            rows.append(row)
    rows.sort(key=lambda row: int(row[0]))
    content = ''.join('\t'.join(row) + '\n' for row in rows).encode('utf-8')
    target.mkdir(parents=True, exist_ok=True)
    compressed = gzip.compress(content, mtime=0)
    (target / 'cities.tsv.gz').write_bytes(compressed)
    manifest = {
        'source': 'https://download.geonames.org/export/dump/',
        'license': 'CC BY 4.0',
        'rows': len(rows),
        'files': {name: hashlib.sha256((source / name).read_bytes()).hexdigest()
                  for name in ['cities15000.zip', 'admin1CodesASCII.txt']},
        'outputSha256': hashlib.sha256(compressed).hexdigest(),
    }
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest))


if __name__ == '__main__':
    build(Path(sys.argv[1]), Path(sys.argv[2]))
