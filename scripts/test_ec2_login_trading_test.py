"""Offline unit tests: never call Angel One and never use real credentials."""
import importlib.util
import io
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name("ec2_login_trading_test.py")
spec = importlib.util.spec_from_file_location("trading_test_login", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LoginTests(unittest.TestCase):
    def test_only_registered_ec2_egress(self):
        def response(value):
            return io.BytesIO(value.encode())
        with mock.patch.object(module.urllib.request, "urlopen", return_value=response("13.202.233.29")):
            self.assertEqual(module.public_ip(), "13.202.233.29")
        with mock.patch.object(module.urllib.request, "urlopen", return_value=response("182.77.77.184")):
            with self.assertRaisesRegex(module.LoginError, "does not match"):
                module.public_ip()

    def test_authenticated_response_extracts_only_jwt(self):
        body = b'{"status": true, "data": {"jwtToken":"aaa.bbb.ccc", "refreshToken":"ignored"}}'
        with mock.patch.object(module.urllib.request, "urlopen", return_value=io.BytesIO(body)) as net:
            jwt = module.login("test-api-key", "test-client", "pin", "123456", "172.31.2.40",
                               "00:11:22:33:44:55")
            self.assertEqual(jwt, "aaa.bbb.ccc")
            req = net.call_args.args[0]
            self.assertEqual(req.get_method(), "POST")
            self.assertEqual(req.get_header("X-privatekey"), "test-api-key")
            self.assertEqual(req.get_header("X-clientpublicip"), "13.202.233.29")
            self.assertEqual(req.full_url, module.LOGIN_URL)

    def test_rejects_login_without_valid_jwt(self):
        response = io.BytesIO(b'{"status": false, "errorcode":"AB1050"}')
        with mock.patch.object(module.urllib.request, "urlopen", return_value=response):
            with self.assertRaisesRegex(module.LoginError, "AB1050"):
                module.login("key", "client", "pin", "123456", "172.31.2.40",
                             "00:11:22:33:44:55")

    def test_atomic_private_file_and_symlink_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "config" / "trading-test.env"
            module.save_private_credentials("key", "a.b.c", "172.31.2.40",
                                            "00:11:22:33:44:55", target)
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(target.parent.stat().st_mode), 0o700)
            self.assertIn("ANGEL_ONE_API_KEY=key", target.read_text())
            self.assertIn("ANGEL_ONE_JWT_TOKEN=a.b.c", target.read_text())
            target.unlink()
            target.symlink_to(Path(directory) / "other")
            with self.assertRaisesRegex(module.LoginError, "symlink"):
                module.save_private_credentials("key", "a.b.c", "172.31.2.40",
                                                "00:11:22:33:44:55", target)


if __name__ == "__main__":
    unittest.main()
