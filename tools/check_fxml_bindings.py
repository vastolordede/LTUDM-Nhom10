#!/usr/bin/env python3
"""Check resource FXML XML, controller fx:ids and handler names without JavaFX runtime."""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
views = root / 'peer-client/src/main/resources/vn/edu/p2p/peer/view'
classes = root / 'peer-client/src/main/java'
fx_namespaces = ('{http://javafx.com/fxml}', '{http://javafx.com/fxml/1}')
def fxattr(elem, name):
    return next((elem.attrib[ns + name] for ns in fx_namespaces if ns + name in elem.attrib), None)
errors = []
checked = 0
for fxml in sorted(views.glob('*.fxml')):
    checked += 1
    try:
        tree = ET.parse(fxml)
    except ET.ParseError as err:
        errors.append(f'{fxml.name}: invalid XML: {err}')
        continue
    controller = fxattr(tree.getroot(), 'controller')
    if not controller:
        errors.append(f'{fxml.name}: missing fx:controller')
        continue
    source = classes / (controller.replace('.', '/') + '.java')
    if not source.is_file():
        errors.append(f'{fxml.name}: missing {source}')
        continue
    content = source.read_text(encoding='utf-8')
    fields = set(re.findall(r'\b([A-Za-z_][A-Za-z0-9_]*)\s*(?:[=;])', content))
    methods = set(re.findall(r'\b(?:void|boolean|String|Object)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(', content))
    ids = set()
    for element in tree.iter():
        ident = fxattr(element, 'id')
        if ident:
            if ident in ids: errors.append(f'{fxml.name}: duplicate fx:id={ident}')
            ids.add(ident)
            if ident not in fields: errors.append(f'{fxml.name}: no field {controller}.{ident}')
        for key, value in element.attrib.items():
            if key.startswith('on') and value.startswith('#') and value[1:] not in methods:
                errors.append(f'{fxml.name}: no handler {controller}.{value[1:]}')

if checked == 0: errors.append('No FXML views found!')
for e in errors: print('ERROR:', e, file=sys.stderr)
print(f'FXML checked: {checked} files; issues: {len(errors)}')
sys.exit(bool(errors))
