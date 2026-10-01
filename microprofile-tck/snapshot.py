"""Preserve actual upstream TCK results, diagnostics and artifact identities outside build/."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--label', required=True, help='New evidence directory name')
args = parser.parse_args()
module = Path(__file__).resolve().parent
root = module.parent
saved = module / 'evidence' / args.label
if saved.exists():
    raise SystemExit('Evidence already exists; choose a new label to preserve prior runs.')
components = json.loads((module / 'components.json').read_text())
saved.mkdir(parents=True)
summary = ['component\tmode\tpassed\tassertion_or_test_failures\tsetup_failures\terrors\tskipped']
runs = {}
for component, coordinates in components.items():
    build = module / component / 'build'
    for task, mode in [('tckSuite', 'native'), ('tckImportedSuite', 'imported'), ('tckReferenceSuite', 'reference')]:
        files = sorted((build / 'test-results' / task).glob('TEST-*.xml'))
        if not files:
            continue
        dest = saved / component / mode
        dest.mkdir(parents=True)
        cases, suites, counts, traces = [], [], Counter(), {}
        for file in files:
            suite = ET.parse(file).getroot()
            suites.append({'class': suite.get('name'), 'timestamp': suite.get('timestamp'), 'seconds': suite.get('time')})
            for case in suite.findall('testcase'):
                name = case.get('name', '')
                failure, error, skip = case.find('failure'), case.find('error'), case.find('skipped')
                setup = name.startswith(('arquillian', 'before', 'setUp', 'setup', 'checkSetup')) or name == 'classMethod'
                if failure is not None and failure.get('message', '').startswith('org.jboss.arquillian.container.spi.client.container.DeploymentException:'):
                    # JUnit can attach a deployment exception to a method rather than classMethod.
                    setup = True
                status = 'error' if error is not None else ('setup_failure' if setup else 'test_failure') if failure is not None else 'skipped' if skip is not None else 'passed'
                counts[status] += 1
                entry = {'class': case.get('classname'), 'name': name, 'seconds': case.get('time'), 'status': status}
                diagnostic = error if error is not None else failure if failure is not None else skip
                if diagnostic is not None:
                    entry['message'] = diagnostic.get('message', '')
                    trace = diagnostic.text or ''
                    if trace:
                        digest = hashlib.sha256(trace.encode()).hexdigest()
                        traces[digest] = trace
                        entry['trace_ref'] = digest
                cases.append(entry)
        (dest / 'cases.json').write_text(json.dumps(cases, indent=2) + '\n')
        (dest / 'traces.json').write_text(json.dumps(traces, indent=2) + '\n')
        deployments = {}
        for folder in sorted((build / 'deployments' / mode).iterdir()) if (build / 'deployments' / mode).exists() else []:
            if folder.is_dir():
                # Capture bootstrap failures and archive membership, without external source or bytecode copies.
                entry = {}
                for filename in ['archive.txt', 'diagnostics.txt', 'sources.txt', 'failure.txt', 'outcome.txt', 'container.txt']:
                    path = folder / filename
                    if path.is_file():
                        entry[filename] = path.read_text()
                for path in folder.glob('http-failure-*.txt'):
                    entry[path.name] = path.read_text()
                deployments[folder.name] = entry
            elif folder.name == 'factory-failure.txt':
                deployments[folder.name] = folder.read_text()
        (dest / 'deployments.json').write_text(json.dumps(deployments, indent=2) + '\n')
        for original, target in [(build / f'artifacts-{mode}.tsv', 'artifacts.tsv'), (build / 'selected-tests.txt', 'selected-tests.txt')]:
            if original.exists():
                shutil.copy2(original, dest / target)
        source_roots = list(Path.home().joinpath('.gradle/caches/modules-2/files-2.1', coordinates['group'], coordinates['artifact'], coordinates['version']).glob('*/*-sources.jar'))
        if component == 'jwt':
            source_roots = list(Path.home().joinpath('.gradle/caches/modules-2/files-2.1', coordinates['group'], coordinates['artifact'], coordinates['version']).glob('*/*-test-sources.jar'))
        runs[f'{component}/{mode}'] = {
            'task': task, 'counts': dict(counts), 'suites': suites,
            'source_artifacts': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in source_roots},
        }
        summary.append('\t'.join(map(str, [component, mode, counts['passed'], counts['test_failure'], counts['setup_failure'], counts['error'], counts['skipped']])))
(saved / 'summary.tsv').write_text('\n'.join(summary) + '\n')
def head(path):
    return subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip()
code = [p for p in module.rglob('*') if p.is_file() and not any(part in ['build', 'evidence', '__pycache__'] for part in p.relative_to(module).parts)]
inventory = {
    'saved_utc': datetime.now(timezone.utc).isoformat(),
    'scope': 'Functional MicroProfile integration, not security testing or certification',
    'cdi_revision': head(root), 'interceptors_revision': head(root / 'build/fuzz-interceptors'),
    'java': subprocess.run(['java', '-version'], text=True, capture_output=True, check=True).stderr.strip(),
    'components': components, 'runs': runs,
    'runner_sha256_at_snapshot': {str(p.relative_to(module)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(code)},
    'evidence_sha256': {str(p.relative_to(saved)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(saved.rglob('*')) if p.is_file()},
    'notes': ['Setup callbacks are separate from failed test assertions; skipped methods did not pass.',
              'Run timestamps are per suite. Helpers evolved during bring-up; see FINDINGS for remaining runner limitations.',
              'Weld reference also enables CDI Full vendor extensions. Imported mode deliberately omits those vendor extensions.'],
}
(saved / 'inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
print('\n'.join(summary))
