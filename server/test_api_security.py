from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from fastapi.testclient import TestClient

import app as app_module


TOKEN = "test-monitor-token"


class ApiSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = TemporaryDirectory()
        app_module.DATA_FILE = Path(self.tmp.name) / "state.json"
        app_module.MONITOR_TOKEN = TOKEN
        app_module.AUTH_FAILURES.clear()
        app_module.state.clear()
        app_module.state.update(app_module.empty_state())
        self.client = TestClient(app_module.app)

    def tearDown(self) -> None:
        self.client.close()
        self.tmp.cleanup()

    def auth_headers(self) -> dict[str, str]:
        return {"X-Monitor-Token": TOKEN}

    def test_health_and_status_have_security_headers(self) -> None:
        health = self.client.get("/api/health")
        status = self.client.get("/api/status", headers=self.auth_headers())

        self.assertEqual(200, health.status_code)
        self.assertEqual(200, status.status_code)
        for response in (health, status):
            self.assertEqual("no-store", response.headers["cache-control"])
            self.assertEqual("nosniff", response.headers["x-content-type-options"])
            self.assertEqual("DENY", response.headers["x-frame-options"])
            self.assertEqual("no-referrer", response.headers["referrer-policy"])

    def test_status_requires_configured_valid_token(self) -> None:
        unauthorized = self.client.get("/api/status")
        self.assertEqual(401, unauthorized.status_code)

        app_module.MONITOR_TOKEN = ""
        unavailable = self.client.get("/api/status", headers=self.auth_headers())
        self.assertEqual(503, unavailable.status_code)

    def test_repeated_authentication_failures_are_throttled(self) -> None:
        original_limit = app_module.AUTH_FAILURE_LIMIT
        app_module.AUTH_FAILURE_LIMIT = 3
        try:
            for _ in range(2):
                response = self.client.get("/api/status", headers={"X-Monitor-Token": "wrong"})
                self.assertEqual(401, response.status_code)

            throttled = self.client.get("/api/status", headers={"X-Monitor-Token": "wrong"})
            self.assertEqual(429, throttled.status_code)
            self.assertGreaterEqual(int(throttled.headers["retry-after"]), 1)

            recovered = self.client.get("/api/status", headers=self.auth_headers())
            self.assertEqual(200, recovered.status_code)
            self.assertFalse(app_module.AUTH_FAILURES)
        finally:
            app_module.AUTH_FAILURE_LIMIT = original_limit

    def test_oversized_or_lengthless_post_is_rejected(self) -> None:
        oversized = self.client.post(
            "/api/status",
            content=b"{}",
            headers={
                **self.auth_headers(),
                "Content-Type": "application/json",
                "Content-Length": str(app_module.MAX_REQUEST_BODY_BYTES + 1),
            },
        )
        self.assertEqual(413, oversized.status_code)
        self.assertEqual("no-store", oversized.headers["cache-control"])

        lengthless = self.client.post(
            "/api/status",
            content=(chunk for chunk in (b"{}",)),
            headers={**self.auth_headers(), "Content-Type": "application/json"},
        )
        self.assertEqual(411, lengthless.status_code)

    def test_valid_update_round_trip(self) -> None:
        response = self.client.post(
            "/api/status",
            headers=self.auth_headers(),
            json={
                "run_id": "api-test",
                "epoch": 1,
                "total_epochs": 5,
                "metric_name": "mIoU",
                "metrics": {"mIoU": 61.5, "loss": 0.4},
            },
        )
        self.assertEqual(200, response.status_code)
        self.assertEqual("api-test", response.json()["run_id"])
        self.assertTrue(app_module.DATA_FILE.exists())


if __name__ == "__main__":
    unittest.main()
