import importlib.util
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

packages = load('package_commands', ROOT / 'tools/package_commands.py')
accessibility = load('accessibility', ROOT / 'armour/accessibility.py')

class SecurityScriptsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT / 'tools/tests')
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)

    def test_list_validation_is_atomic(self):
        path = self.path / 'list.txt'
        for invalid in ('com.example;id', 'com.example$(id)', 'com.example extra', '-bad', 'com.example\t;id'):
            path.write_text('com.valid.app\n' + invalid + '\n')
            result = subprocess.run(['python3', '-I', str(ROOT / 'tools/package_commands.py'), 'disable', str(path)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('', result.stdout)
        path.write_text('# comment\r\ncom.valid.app # inline\r\nandroid\r\n')
        self.assertEqual(['pm disable-user --user 0 com.valid.app', 'pm disable-user --user 0 android', 'pm list packages -d'], packages.commands('disable', path))
        with self.assertRaises(ValueError): packages.commands('restore', path, 'com.launcher;id')
        self.assertIn('pm enable com.launcher', packages.commands('restore', path, 'com.launcher'))

    def test_accessibility_preserves_other_services(self):
        other = 'com.reader/.ScreenReader:com.buttons/com.buttons.Mapper'
        added = accessibility.commands('add', other)
        self.assertIn(other + ':' + accessibility.COMPONENT, added)
        removed = accessibility.commands('remove', other + ':' + accessibility.COMPONENT)
        self.assertIn(other + '\n', removed)
        self.assertTrue(removed.endswith('accessibility_enabled 1'))
        self.assertTrue(accessibility.commands('remove', accessibility.COMPONENT).endswith('accessibility_enabled 0'))
        self.assertIn(accessibility.COMPONENT, accessibility.commands('add', 'null'))
        self.assertEqual(1, accessibility.commands('add', accessibility.COMPONENT).count(accessibility.COMPONENT))
        with self.assertRaises(ValueError): accessibility.commands('add', 'bad;id')

    def test_cli_requires_and_passes_pin_for_all_requests(self):
        bindir = self.path / 'bin'; bindir.mkdir()
        marker = self.path / 'curl.args'
        curl = bindir / 'curl'
        curl.write_text('#!/usr/bin/env python3\nimport os,sys,json\nopen(os.environ["CURL_ARGS"],"w").write(json.dumps(sys.argv[1:]))\nprint(\'{"done":"ok"}\')\n')
        curl.chmod(0o755)
        env = dict(os.environ, HOME=str(self.path), TV_HOST='127.0.0.1', CURL_ARGS=str(marker), PATH=str(bindir) + os.pathsep + os.environ['PATH'])
        def run(*args):
            return subprocess.run(['bash', str(ROOT / 'armour/tv'), *args], env=env, capture_output=True, text=True)
        self.assertNotEqual(0, run('status').returncode)
        self.assertFalse(marker.exists())
        config = self.path / '.config/vibe-tv'; config.mkdir(parents=True)
        (config / 'remote.pubkey').write_text('test-pin')
        (config / 'remote.token').write_text('secret')
        file = self.path / 'video.mp4'; file.write_bytes(b'data')
        import json
        for args in [('status',), ('pair',), ('present', str(file))]:
            self.assertEqual(0, run(*args).returncode)
            argv = json.loads(marker.read_text())
            self.assertEqual(str(config / 'remote.pubkey'), argv[argv.index('--pinnedpubkey') + 1])
            self.assertIn('X-Token: secret', argv)

class PinTransportTest(unittest.TestCase):
    def test_untrusted_server_never_receives_token(self):
        import ssl
        import socket
        import threading
        with tempfile.TemporaryDirectory(dir=ROOT / 'tools/tests') as directory:
            directory = Path(directory)
            for name in ('trusted', 'impostor'):
                subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost', '-keyout', str(directory / (name + '.key')), '-out', str(directory / (name + '.pem'))], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            pin = directory / 'pin.pem'
            with pin.open('wb') as out:
                subprocess.run(['openssl', 'x509', '-in', str(directory / 'trusted.pem'), '-pubkey', '-noout'], check=True, stdout=out)
            for identity, expected in [('trusted', 0), ('impostor', 90)]:
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.load_cert_chain(str(directory / (identity + '.pem')), str(directory / (identity + '.key')))
                received = []
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(5)
                    def serve():
                        try:
                            connection, _ = listener.accept()
                            with connection:
                                connection.settimeout(5)
                                with context.wrap_socket(connection, server_side=True) as tls:
                                    data = tls.recv(4096)
                                    received.append(data)
                                    if data:
                                        tls.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}')
                        except (ssl.SSLError, OSError):
                            pass
                    thread = threading.Thread(target=serve); thread.start()
                    result = subprocess.run(['curl', '-sS', '--insecure', '--pinnedpubkey', str(pin), '--max-time', '5', '--noproxy', '*', '-H', 'X-Token: test-secret', f'https://127.0.0.1:{listener.getsockname()[1]}'], capture_output=True)
                    thread.join(6)
                    self.assertFalse(thread.is_alive())
                    self.assertEqual(expected, result.returncode, result.stderr.decode())
                    if identity == 'trusted': self.assertIn(b'X-Token: test-secret', b''.join(received))
                    else: self.assertEqual(b'', b''.join(received))

if __name__ == '__main__': unittest.main()
