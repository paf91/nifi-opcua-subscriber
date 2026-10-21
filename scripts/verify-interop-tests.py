#!/usr/bin/env python3
"""Require successful independent-server tests; skipped or missing suites fail CI."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

SUITES = ('Open62541Test', 'Open62541RecoveryTest')


def verify(directory):
    total = 0
    for name in SUITES:
        path = directory / ('TEST-io.github.paf91.nifi.opcua.' + name + '.xml')
        if not path.is_file():
            raise ValueError('Missing independent-server report: ' + name)
        suite = ET.parse(path).getroot()
        cases = suite.findall('testcase')
        if not cases or any(case.find(state) is not None for case in cases
                            for state in ('skipped', 'failure', 'error')):
            raise ValueError('Independent-server suite must pass without skips: ' + name)
        total += len(cases)
    return total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    print('Independent-server tests passed: ' + str(verify(args.directory)))


if __name__ == '__main__':
    main()
