import os
import sys
import unittest
from unittest import mock

# thor_mc_hud imports GTK and cairo at module load; stub them so the pure tracker can be tested anywhere.
for _name in ("gi", "gi.repository", "cairo"):
    sys.modules.setdefault(_name, mock.MagicMock())
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from thor_mc_hud import IconRequests  # noqa: E402


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class IconRequestsTest(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.icons = IconRequests(pending_timeout=5.0, failure_backoff=5.0, clock=self.clock)

    def test_first_request_goes_out_and_repeats_are_suppressed(self):
        self.assertTrue(self.icons.should_request("minecraft:stone"))
        for _ in range(100):
            self.assertFalse(self.icons.should_request("minecraft:stone"))

    def test_unanswered_request_is_retried_after_timeout(self):
        self.assertTrue(self.icons.should_request("minecraft:stone"))
        self.clock.now = 4.9
        self.assertFalse(self.icons.should_request("minecraft:stone"))
        self.clock.now = 5.0
        self.assertTrue(self.icons.should_request("minecraft:stone"))

    def test_failed_item_backs_off_then_retries(self):
        self.assertTrue(self.icons.should_request("minecraft:mystery"))
        self.icons.failed("minecraft:mystery")
        self.clock.now = 4.0
        self.assertFalse(self.icons.should_request("minecraft:mystery"))
        self.clock.now = 5.0
        self.assertTrue(self.icons.should_request("minecraft:mystery"))

    def test_answered_item_clears_pending_so_a_later_request_is_allowed(self):
        self.assertTrue(self.icons.should_request("minecraft:dirt"))
        self.icons.answered("minecraft:dirt")
        self.assertTrue(self.icons.should_request("minecraft:dirt"))

    def test_items_are_independent(self):
        self.assertTrue(self.icons.should_request("a"))
        self.assertTrue(self.icons.should_request("b"))
        self.icons.failed("a")
        self.assertFalse(self.icons.should_request("b"))

    def test_reset_allows_immediate_rerequest(self):
        self.assertTrue(self.icons.should_request("minecraft:stone"))
        self.icons.failed("minecraft:mystery")
        self.icons.reset()
        self.assertTrue(self.icons.should_request("minecraft:stone"))
        self.assertTrue(self.icons.should_request("minecraft:mystery"))


if __name__ == "__main__":
    unittest.main()
