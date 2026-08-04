"""Regression test: a transient exception from claim_job (network blip, DNS hiccup)
must not crash the worker process -- it should be logged and the loop retried."""

import pytest

from worker_common.loop import run_sse_loop


class _StopTest(BaseException):
    """Sentinel used to end the infinite run_sse_loop for testing purposes.

    Deliberately NOT a subclass of Exception so run_sse_loop's `except Exception`
    handlers can't swallow it -- it must propagate all the way out.
    """


class _FakeClient:
    def __init__(self, claim_job_side_effects):
        self._claim_job_side_effects = iter(claim_job_side_effects)
        self._headers = {}
        self._client = type("_C", (), {"headers": {}})()
        self.worker_id = "fake-worker-id"

    def claim_job(self, kind):
        effect = next(self._claim_job_side_effects)
        if isinstance(effect, BaseException):
            raise effect
        return effect


def test_transient_claim_job_error_does_not_crash_the_loop():
    client = _FakeClient(
        [
            ConnectionError("[Errno 8] nodename nor servname provided, or not known"),
            _StopTest(),
        ]
    )

    with pytest.raises(_StopTest):
        run_sse_loop(
            client,
            job_kinds=["fake_kind"],
            process_fn=lambda job: None,
            poll_interval=10,
        )
