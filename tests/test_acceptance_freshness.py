"""Recovery evidence for the synthetic, once-per-second Load.Tag server."""
from datetime import datetime, timezone
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).resolve().parents[1] / 'scripts/acceptance.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class RecoveryFreshnessTest(unittest.TestCase):
    NOW = 1700000000

    def record(self, source_age=1, received_age=0):
        def instant(age):
            return datetime.fromtimestamp(self.NOW - age, timezone.utc).isoformat()
        return {'sourceTimestamp': instant(source_age), 'receivedTimestamp': instant(received_age)}

    def test_accepts_fresh_source_and_receipt(self):
        result = acceptance.validate_fresh_records([self.record()], self.NOW)
        self.assertEqual(self.NOW - 1, result['newestSourceTimestamp'])

    def test_rejects_stale_source_despite_recent_receipt(self):
        with self.assertRaisesRegex(AssertionError, 'Stale sourceTimestamp'):
            acceptance.validate_fresh_records([self.record(source_age=3600)], self.NOW)

    def test_one_fresh_record_cannot_hide_stale_values(self):
        with self.assertRaisesRegex(AssertionError, 'Stale sourceTimestamp'):
            acceptance.validate_fresh_records([self.record(source_age=3600), self.record()], self.NOW)

    def test_rejects_stale_receipt(self):
        with self.assertRaisesRegex(AssertionError, 'Stale receivedTimestamp'):
            acceptance.validate_fresh_records([self.record(received_age=40)], self.NOW)

    def test_requires_source_time_for_synthetic_tags(self):
        record = self.record()
        record['sourceTimestamp'] = None
        with self.assertRaisesRegex(AssertionError, 'Missing sourceTimestamp'):
            acceptance.validate_fresh_records([record], self.NOW)

    def test_rejects_future_source_time(self):
        with self.assertRaisesRegex(AssertionError, 'Future sourceTimestamp'):
            acceptance.validate_fresh_records([self.record(source_age=-3600)], self.NOW)
