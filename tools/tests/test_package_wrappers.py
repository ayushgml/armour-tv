"""Exercise the real Bash/PowerShell wrappers against a fake ADB client, never a TV."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PackageWrappersTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / 'tools/tests')
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.tools = self.repo / 'tools'
        self.tools.mkdir()
        for name in ('package_commands.py', 'disable.sh', 'restore.sh', 'disable.ps1', 'restore.ps1'):
            shutil.copy2(ROOT / 'tools' / name, self.tools / name)
        subprocess.run([sys.executable, '-m', 'venv', '--without-pip', str(self.tools / 'venv')], check=True, capture_output=True)
        if os.name != 'nt':
            scripts = self.tools / 'venv/Scripts'
            scripts.mkdir()
            (scripts / 'python.exe').symlink_to(self.tools / 'venv/bin/python')
        self.log = self.repo / 'adb.log'
        (self.tools / 'tv.py').write_text(
            'import os,sys\nfrom pathlib import Path\n'
            'Path(os.environ["TEST_ADB_LOG"]).write_text(sys.stdin.read(), encoding="utf-8")\n'
            'sys.exit(int(os.environ.get("TEST_ADB_EXIT", "0")))\n', encoding='utf-8')
        self.list = self.repo / 'packages with spaces.txt'
        self.list.write_text('com.example.app # comment\nandroid\n', encoding='utf-8')
        self.env = dict(os.environ, TV_HOST='test.invalid', TEST_ADB_LOG=str(self.log))
        self.runners = []
        pwsh = shutil.which('pwsh') or shutil.which('powershell')
        if pwsh:
            self.runners.append(('ps1', [pwsh, '-NoLogo', '-NoProfile', '-File']))
        if os.name != 'nt':
            self.runners.append(('sh', ['bash']))
        if not self.runners:
            self.fail('No supported shell available')

    def run_wrapper(self, action, extension, prefix, stock=None):
        if self.log.exists():
            self.log.unlink()
        args = prefix + [str(self.tools / (action + '.' + extension)), str(self.list)]
        if stock is not None:
            args += ['-Stock', stock] if extension == 'ps1' else [stock]
        return subprocess.run(args, env=self.env, cwd=self.repo, capture_output=True, text=True, timeout=30)

    def test_valid_lists(self):
        for extension, prefix in self.runners:
            for action in ('disable', 'restore'):
                with self.subTest(shell=extension, action=action):
                    result = self.run_wrapper(action, extension, prefix)
                    self.assertEqual(0, result.returncode, result.stderr)
                    text = self.log.read_text(encoding='utf-8')
                    self.assertIn('com.example.app', text)
                    self.assertIn('pm list packages -d', text)
                    self.assertNotIn('# comment', text)

    def test_invalid_list_never_opens_adb(self):
        self.list.write_text('com.valid.app\ncom.example;id\n', encoding='utf-8')
        for extension, prefix in self.runners:
            for action in ('disable', 'restore'):
                result = self.run_wrapper(action, extension, prefix)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.log.exists())

    def test_invalid_stock_never_opens_adb(self):
        for extension, prefix in self.runners:
            result = self.run_wrapper('restore', extension, prefix, 'com.launcher;id')
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(self.log.exists())

    def test_adb_failure_is_reported(self):
        self.env['TEST_ADB_EXIT'] = '7'
        for extension, prefix in self.runners:
            for action in ('disable', 'restore'):
                result = self.run_wrapper(action, extension, prefix)
                self.assertNotEqual(0, result.returncode)
                self.assertTrue(self.log.exists())


if __name__ == '__main__':
    unittest.main()
