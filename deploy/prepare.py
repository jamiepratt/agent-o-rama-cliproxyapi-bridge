#!/usr/bin/env python3
"""Download pinned public artifacts into a user-owned cache. Never install or start."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request


def verify(path, algorithm, expected):
    digest = hashlib.new(algorithm)
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise ValueError('Artifact checksum mismatch')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cache', type=Path)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    pins = json.loads(Path(__file__).with_name('artifacts.json').read_text())
    for pin in pins:
        path = args.cache / pin['file']
        if not path.exists():
            temporary = path.with_suffix('.partial')
            try:
                urllib.request.urlretrieve(pin['url'], temporary)
                verify(temporary, pin['algorithm'], pin['digest'])
                temporary.replace(path)
            finally:
                temporary.unlink(missing_ok=True)
        verify(path, pin['algorithm'], pin['digest'])
        print(f"Verified {pin['file']}")

if __name__ == '__main__':
    main()
