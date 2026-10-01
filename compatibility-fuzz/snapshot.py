"""Save a successful campaign's results and source diagnostics outside ignored build outputs."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--weld', default='6.0.4.Final')
parser.add_argument('--label', help='Evidence directory name; preserves earlier revision snapshots')
args = parser.parse_args()
module = Path(__file__).resolve().parent
root = module.parent
campaign = module / 'build' / 'campaigns' / args.weld
saved = module / 'evidence' / (args.label or ('weld-' + args.weld))
saved.mkdir(parents=True, exist_ok=True)
rows = []
for name in ['results.tsv', 'source-results.tsv', 'artifacts.tsv']:
    source = campaign / name
    if not source.is_file():
        raise SystemExit(f'Missing {source}; run the campaign first.')
    shutil.copy2(source, saved / name)
    if name != 'artifacts.tsv':
        rows.extend(line.split('\t') for line in source.read_text().splitlines())
if any('HARNESS_ERROR' in '\t'.join(row) for row in rows):
    raise SystemExit('Harness failures must be corrected before saving evidence.')
if (saved / 'sources').exists():
    shutil.rmtree(saved / 'sources')

for source_number, row in enumerate(row for row in rows if row[0] == 'source'):
    origin = campaign / 'source-mutations' / row[1]
    destination = saved / 'sources' / row[1]
    destination.mkdir(parents=True, exist_ok=True)
    source = origin / f'Mutation{source_number}.java'
    shutil.copy2(source, destination / source.name)
    if row[-1] == 'DIFF':
        for implementation in ['weld', 'micronaut']:
            for source in (origin / implementation).glob('*.txt'):
                target = destination / implementation / source.name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)

def git_head(directory):
    return subprocess.check_output(['git', '-C', str(directory), 'rev-parse', 'HEAD'], text=True).strip()

def test_counts(folder):
    counts = {key: 0 for key in ['tests', 'failures', 'errors', 'skipped']}
    for file in folder.glob('TEST-*.xml'):
        result = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(result.attrib.get(key, 0))
    return counts

families = {}
for family, case, reference, actual, status in rows:
    counts = families.setdefault(family, {'comparisons': 0, 'differences': 0})
    counts['comparisons'] += 1
    counts['differences'] += status == 'DIFF'
driver = ET.parse(module / 'build/test-results/test/TEST-org.example.cdi.fuzz.DifferentialFuzzTest.xml').getroot()
seed = int(re.search(r'FUZZ seed=(\d+)', driver.findtext('system-out', '')).group(1))
zone_file = Path('/usr/share/zoneinfo/Europe/Bucharest')
if zone_file.is_file():
    with zone_file.open('rb') as zone_data:
        run_zone = ZoneInfo.from_file(zone_data, key='Europe/Bucharest')
else:
    run_zone = ZoneInfo('Europe/Bucharest')
run_time = datetime.fromisoformat(driver.attrib['timestamp'].replace('Z', '+00:00')).astimezone(run_zone)
inventory = {
    'date': run_time.date().isoformat(),
    'scope': 'Functional CDI Lite compatibility',
    'cdi_revision': git_head(root),
    'interceptors_revision': git_head(root / 'build' / 'fuzz-interceptors'),
    'weld': args.weld,
    'seed': hex(seed),
    'command': f'./gradlew :micronaut-compatibility-fuzz:test -PcompatibilityFuzz -PjakartaInterceptorsDir=build/fuzz-interceptors -PfuzzWeldVersion={args.weld} -PfuzzSeed={seed}',
    'java': subprocess.run(['java', '-version'], text=True, capture_output=True, check=True).stderr.strip(),
    'comparisons': len(rows),
    'differing_rows': sum(row[-1] == 'DIFF' for row in rows),
    'families': families,
    'suites': {
        'CDI Java': test_counts(root / 'test-suite-java/build/test-results/test'),
        'CDI configured TCK': test_counts(root / 'cdi-tck/build/test-results/tckSuite'),
        'Interceptors Java': test_counts(root / 'build/fuzz-interceptors/test-suite-java/build/test-results/test'),
        'Differential drivers': test_counts(module / 'build/test-results/test'),
    },
    'evidence_hashes': {
        name: hashlib.sha256((saved / name).read_bytes()).hexdigest()
        for name in ['results.tsv', 'source-results.tsv', 'artifacts.tsv']
    },
}
(saved / 'inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
print(json.dumps({'comparisons': inventory['comparisons'], 'differing_rows': inventory['differing_rows'], 'saved': str(saved)}, indent=2))
