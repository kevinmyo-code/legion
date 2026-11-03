package com.kevin.legion.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0071's ask-first list: what the browser asks before tapping or typing. */
class AskFirstTest {
    private fun page(url: String = "https://shop.example/item/1", payment: Boolean = false) =
        PageSnapshot(url, "Item", "", false, emptyList(), false, payment)

    private fun button(label: String, submit: Boolean = false, search: Boolean = false) =
        PageElement(1, ElementKind.BUTTON, label, submit = submit, inForm = submit, searchForm = search)

    @Test fun `each ruled word asks first`() {
        val labels = listOf(
            "Pay now", "Buy it now", "Place order", "Checkout", "Check out", "Send", "Post", "Delete account",
            "Book a table", "Reserve", "Confirm", "Subscribe", "Transfer", "Purchase", "Publish", "Remove",
            "Add to cart", "Submit",
        )
        for (label in labels) assertNotNull(label, AskFirst.forClick(button(label), page()))
    }

    @Test fun `words inside other words do not`() {
        for (label in listOf("Facebook", "Posts", "Bookmarks", "Ordered list", "Menu", "Images", "Next page")) {
            assertFalse(label, AskFirst.risky(label))
        }
        assertNull(AskFirst.forClick(PageElement(1, ElementKind.LINK, "Facebook"), page()))
    }

    @Test fun `a link labelled order online asks too, since it leads to ordering`() {
        assertNotNull(AskFirst.forClick(PageElement(2, ElementKind.LINK, "Order online"), page()))
    }

    @Test fun `submitting a form asks, a search box does not`() {
        assertNotNull(AskFirst.forClick(button("Continue", submit = true), page()))
        assertNull(AskFirst.forClick(button("Google Search", submit = true, search = true), page()))
    }

    @Test fun `any button on a checkout or payment page asks`() {
        assertNotNull(AskFirst.forClick(button("Continue"), page("https://shop.example/checkout/step2")))
        assertNotNull(AskFirst.forClick(button("Continue"), page("https://pay.google.com/x")))
        assertNotNull(AskFirst.forClick(button("Next"), page(payment = true)))
        assertNull(AskFirst.forClick(button("Next"), page()))
    }

    @Test fun `a search for paying is not a payment page`() {
        assertFalse(AskFirst.isPaymentPage(page("https://www.google.com/search?q=how+to+pay+a+parking+ticket")))
    }

    @Test fun `typing on a payment page or into a card field asks`() {
        val field = PageElement(3, ElementKind.FIELD, "Name on card")
        assertNotNull(AskFirst.forType(field, page("https://shop.example/payment"), submit = false))
        assertNotNull(AskFirst.forType(field.copy(sensitive = true), page(), submit = false))
        assertNull(AskFirst.forType(PageElement(3, ElementKind.FIELD, "Email"), page(), submit = false))
    }

    @Test fun `typing and submitting a form asks, a search box does not`() {
        val inForm = PageElement(4, ElementKind.FIELD, "Comment", inForm = true)
        assertNotNull(AskFirst.forType(inForm, page(), submit = true))
        assertNull(AskFirst.forType(inForm, page(), submit = false))
        assertNull(AskFirst.forType(inForm.copy(searchForm = true), page(), submit = true))
    }

    @Test fun `the question quotes the label`() {
        assertTrue(AskFirst.forClick(button("Place order"), page())!!.contains("\"Place order\""))
    }
}
