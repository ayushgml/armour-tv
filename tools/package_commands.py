#!/usr/bin/env python3
"""Validate the entire package list before emitting any ADB shell commands."""
import argparse
from pathlib import Path
import re

PACKAGE = re.compile(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*', re.ASCII)


def validate(value):
    if not PACKAGE.fullmatch(value):
        raise ValueError(f'Invalid Android package name: {value!r}')
    return value


def commands(action, path, stock='com.google.android.tvlauncher'):
    packages = []
    for number, line in enumerate(Path(path).read_text(encoding='utf-8-sig').splitlines(), 1):
        value = line.split('#', 1)[0].strip()
        if value:
            try:
                packages.append(validate(value))
            except ValueError as error:
                raise ValueError(f'{path}:{number}: {error}') from error
    if action == 'disable':
        return [f'pm disable-user --user 0 {p}' for p in packages] + ['pm list packages -d']
    validate(stock)
    return [f'pm enable {p}' for p in packages] + [
        f'pm enable {stock}',
        'cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief '
        f'-a android.intent.action.MAIN -c android.intent.category.HOME {stock} | tail -n 1)',
        'settings put global window_animation_scale 1.0',
        'settings put global transition_animation_scale 1.0',
        'settings put global animator_duration_scale 1.0',
        'pm list packages -d',
    ]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['disable', 'restore'])
    parser.add_argument('path')
    parser.add_argument('stock', nargs='?', default='com.google.android.tvlauncher')
    args = parser.parse_args()
    try:
        batch = commands(args.action, args.path, args.stock)
    except (ValueError, OSError) as error:
        parser.exit(1, f'{error}\n')
    print('\n'.join(batch))
