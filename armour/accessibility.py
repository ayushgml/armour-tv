#!/usr/bin/env python3
"""Merge/remove Armour without changing unrelated enabled accessibility services."""
import re
import shlex
import sys

COMPONENT = 'com.ayush.armour/.InputKeyService'
ALIASES = {COMPONENT, 'com.ayush.armour/com.ayush.armour.InputKeyService'}


def commands(action, current):
    current = current.strip()
    services = [] if current in ('', 'null') else current.split(':')
    for service in services:
        if not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', service):
            raise ValueError('Unexpected accessibility settings; refusing to overwrite them')
    services = [service for service in services if service not in ALIASES]
    if action == 'add':
        services.append(COMPONENT)
    elif action != 'remove':
        raise ValueError('Expected add or remove')
    value = shlex.quote(':'.join(services))
    return f'settings put secure enabled_accessibility_services {value}\nsettings put secure accessibility_enabled {1 if services else 0}'


if __name__ == '__main__':
    try:
        print(commands(sys.argv[1], sys.stdin.read()))
    except (ValueError, IndexError) as error:
        sys.exit(str(error))
