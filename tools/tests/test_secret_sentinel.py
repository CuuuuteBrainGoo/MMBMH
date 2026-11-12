import contextlib
import io
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import _secret_sentinel as sentinel


class SecretSentinelTest(unittest.TestCase):
    def test_finds_removed_credential_in_history_and_unicode_worktree_path(self):
        with tempfile.TemporaryDirectory(prefix="sentinel-test-") as temp:
            repo = pathlib.Path(temp)

            def git(*args):
                return subprocess.run(["git", *args], cwd=repo, check=True,
                                      capture_output=True, text=True, encoding="utf-8")

            git("init", "--quiet")
            git("config", "user.name", "Scanner Test")
            git("config", "user.email", "scanner@example.invalid")
            sample = "sk-" + "testvalue" * 5
            file = repo / "配置记录.txt"
            file.write_text("api_key=" + sample, encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "fixture")
            file.write_text("no credential", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "remove fixture")

            original_run = sentinel.run
            with patch.object(sentinel, "REPO", str(repo)), \
                    patch.object(sentinel, "run", side_effect=lambda cmd: original_run(cmd, cwd=str(repo))), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, len(sentinel.scan_history()))
                self.assertEqual([], sentinel.scan_worktree())
                file.write_text("api_key=" + sample, encoding="utf-8")
                self.assertEqual(1, len(sentinel.scan_worktree()))

    def test_failed_history_search_aborts(self):
        responses = [subprocess.CompletedProcess([], 0, "a" * 40, ""),
                     subprocess.CompletedProcess([], 128, "", "invalid pattern")]
        with patch.object(sentinel, "run", side_effect=responses), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(sentinel.SentinelAbort):
                sentinel.scan_history()

    def test_failed_worktree_listing_aborts(self):
        failure = subprocess.CompletedProcess([], 128, "", "not a repository")
        with patch.object(sentinel, "run", return_value=failure), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(sentinel.SentinelAbort):
                sentinel.scan_worktree()


if __name__ == "__main__":
    unittest.main()
