import unittest
from datetime import datetime, timedelta, timezone

from catchup.models import Signal
from catchup.store import MIGRATIONS, Store

NOW = datetime(2026, 10, 24, 12, tzinfo=timezone.utc)


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")

    def test_migrated(self):
        version = self.store.con.execute("pragma user_version").fetchone()[0]
        self.assertEqual(version, len(MIGRATIONS))

    def test_add_dedups_and_counts_new(self):
        s = Signal(url="https://a", title="A", source="x", published=NOW, extra={"k": 1})
        self.assertEqual(self.store.add([s, s], now=NOW), 1)
        self.assertEqual(self.store.add([s], now=NOW), 0)

    def test_recent_window_and_missing_published(self):
        old = Signal(url="https://old", title="old", source="x", published=NOW - timedelta(days=10))
        undated = Signal(url="https://undated", title="u", source="x")  # 无发布时间时按抓取时间记
        self.store.add([old, undated], now=NOW)
        got = self.store.recent(NOW - timedelta(days=1), NOW)
        self.assertEqual([s.url for s in got], ["https://undated"])
        self.assertEqual(got[0].published, NOW)

    def test_last_runs(self):
        self.store.record_run("x", NOW, 3, 1)
        self.store.record_run("x", NOW, 0, 0, "boom")
        self.assertEqual(self.store.last_runs()["x"]["error"], "boom")


if __name__ == "__main__":
    unittest.main()
