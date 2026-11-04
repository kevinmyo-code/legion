"""amazon_orders.py: the page text it reads, and the pages it may open (no browser here)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import amazon_orders as A  # noqa: E402

CARD = """ORDER PLACED
November 1, 2026
TOTAL
$24.99
SHIP TO
Kevin
ORDER # 112-0000000-0000000
Arriving Thursday
Package was handed to a carrier
Anker USB-C Charger
Track package
"""

TRACKING = """Your Orders
Arriving Thursday
Out for delivery
Latest update: Departed Amazon facility, Houston TX
Shipped with Amazon
See all updates
"""


class TestParsing(unittest.TestCase):
    def test_a_card_gives_its_date_and_first_status(self):
        order = A.parse_card(CARD)
        self.assertEqual(order.placed, "November 1, 2026")
        self.assertEqual(order.status, "Arriving Thursday")

    def test_a_card_with_no_status_says_nothing(self):
        self.assertEqual(A.parse_card("ORDER PLACED\nMay 2\nTOTAL\n$3").status, "")

    def test_tracking_keeps_the_status_lines_only(self):
        self.assertEqual(A.parse_tracking(TRACKING),
                         "Arriving Thursday; Out for delivery; Latest update: Departed Amazon facility, Houston TX")

    def test_delivered_orders_are_not_tracked(self):
        self.assertTrue(A.delivered("Delivered November 2"))
        self.assertFalse(A.delivered("Arriving Thursday"))

    def test_spoken_lines_name_item_status_and_tracking(self):
        order = A.Order(placed="November 1, 2026", status="Arriving Thursday",
                        items=["Anker USB-C Charger", "Cable"], tracking="Out for delivery")
        self.assertEqual(A.spoken([order]), "Placed November 1, 2026: Anker USB-C Charger and 1 more. "
                         "Status: Arriving Thursday. Live tracking: Out for delivery.")
        self.assertEqual(A.spoken([]), "No recent orders on the page.")


class TestReadOnly(unittest.TestCase):
    def test_only_the_order_list_and_tracking_pages(self):
        self.assertTrue(A.allowed(A.ORDERS_URL))
        self.assertTrue(A.allowed("https://www.amazon.com/gp/your-account/ship-track?itemId=x&orderId=y"))
        self.assertTrue(A.allowed("https://www.amazon.com/progress-tracker/package/ref=x?orderId=y"))
        for url in ("https://www.amazon.com/gp/buy/spc/handlers/display.html",
                    "https://www.amazon.com/gp/css/order/edit.html?orderID=y",
                    "https://www.amazon.com/returns/cart",
                    "https://www.amazon.com/dp/B000",
                    "https://evil.example/gp/css/order-history",
                    "http://www.amazon.com/gp/css/order-history"):
            self.assertFalse(A.allowed(url), url)

    def test_no_profile_means_sign_in_needed(self):
        orig = A.PROFILE
        A.PROFILE = Path(__file__).parent / "no-such-profile-dir"
        try:
            try:
                import playwright  # noqa: F401
            except ImportError:
                self.skipTest("playwright not installed")
            self.assertEqual(A.main([]), 3)
        finally:
            A.PROFILE = orig


if __name__ == "__main__":
    unittest.main()
