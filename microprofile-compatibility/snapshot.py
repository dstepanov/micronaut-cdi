"""Preserve a validated compatibility campaign, without compiled output or upstream source copies."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--label', default='libraries', help='Evidence directory name')
args = parser.parse_args()
module = Path(__file__).resolve().parent
root = module.parent
campaign = module / 'build/campaign'
saved = module / 'evidence' / args.label
driver = ET.parse(module / 'build/test-results/test/TEST-org.example.mp.CompatibilityTest.xml').getroot()
if any(int(driver.get(k, '0')) for k in ['failures', 'errors', 'skipped']):
    raise SystemExit('Only snapshot a run with a valid Weld reference and passing positive controls.')
rows = [r.split('\t') for r in (campaign / 'results.tsv').read_text().splitlines()]
if any(r[1] != 'OK:' + r[3] for r in rows):
    raise SystemExit('A reference scenario did not meet its expected result.')
if saved.exists():
    shutil.rmtree(saved)
saved.mkdir(parents=True)
for name in ['results.tsv', 'artifacts.tsv', 'origins.tsv']:
    shutil.copy2(campaign / name, saved / name)
for i, row in enumerate(rows):
    source = campaign / row[0] / f'Integration{i}.java'
    dest = saved / 'sources' / row[0]
    dest.mkdir(parents=True)
    shutil.copy2(source, dest / source.name)
    for container in ['weld', 'micronaut']:
        for diagnostic in (source.parent / container).glob('*.txt'):
            target = dest / container / diagnostic.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(diagnostic, target)
families = {}
for row in rows:
    family = row[0].split('-')[0]
    counts = families.setdefault(family, {'comparisons': 0, 'differences': 0})
    counts['comparisons'] += 1
    counts['differences'] += row[-1] == 'DIFF'
def head(path):
    return subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip()
inventory = {
    'timestamp_utc': driver.get('timestamp'),
    'scope': 'Functional MicroProfile library compatibility; no vulnerability testing',
    'cdi_revision': head(root),
    'interceptors_revision': head(root / 'build/fuzz-interceptors'),
    'local_config_revision': head(root / 'build/microprofile-config-under-test'),
    'comparisons': len(rows),
    'differences': sum(r[-1] == 'DIFF' for r in rows),
    'families': families,
    'command': './gradlew :micronaut-microprofile-compatibility:test -PmicroprofileCompatibility -PjakartaInterceptorsDir=build/fuzz-interceptors',
    'java': subprocess.run(['java', '-version'], text=True, capture_output=True, check=True).stderr.strip(),
    'evidence_sha256': {str(p.relative_to(saved)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(saved.rglob('*')) if p.is_file()},
}
(saved / 'inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
print(f'Saved {len(rows)} comparisons, {inventory["differences"]} differences.')
