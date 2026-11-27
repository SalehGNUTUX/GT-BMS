// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.junit.Assert.assertEquals
import org.junit.Test

class PriceBookTest {
    private fun p(kwh: Double, fuel: Double = 14.5) = Prices(0, kwh, "MAD", fuel, 2.5)

    @Test fun aNewPriceChangesOnlyWhatComesAfter() {
        val day = 86_400_000L
        val book = PriceBook.change(emptyList(), 10 * day, p(1.5), before = p(1.2))
        assertEquals(1.2, PriceBook.at(book, 3 * day)!!.kwh, 0.0)     // an old trip keeps its price
        assertEquals(1.5, PriceBook.at(book, 11 * day)!!.kwh, 0.0)    // a new one gets the new price
        assertEquals(book, PriceBook.parse(PriceBook.toJson(book)))
    }

    @Test fun typingIsOneChange() {
        var book = PriceBook.change(emptyList(), 1_000_000, p(1.0), before = p(1.2))
        book = PriceBook.change(book, 1_060_000, p(1.5))
        assertEquals(2, book.size); assertEquals(1.5, book.last().kwh, 0.0)
        book = PriceBook.change(book, 1_120_000, p(1.2))                // back to the old value: undone
        assertEquals(1, book.size)
        assertEquals(book, PriceBook.change(book, 9_000_000, p(1.2)))   // same values: nothing new
    }
}
