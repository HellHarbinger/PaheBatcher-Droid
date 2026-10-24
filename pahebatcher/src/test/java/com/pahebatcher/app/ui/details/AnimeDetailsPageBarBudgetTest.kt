package com.pahebatcher.app.ui.details

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 12 UI review — the Go-to-page field's visibility is derived from the
 * pager's number budget, so the budget itself must adapt to the strip's
 * available width and font scale. A wide strip fits every page (budget ==
 * lastPage → no ellipsis → the field is unnecessary); a narrow one collapses to
 * the phone floor. A strip wide enough for every page of a many-page title
 * needs a window wider than the ~461dp test device provides, so that growth is
 * verified here as a deterministic function rather than through Compose.
 */
class AnimeDetailsPageBarBudgetTest {

    @Test
    fun aWideStripFitsEveryPageSoGoToPageIsUnnecessary() {
        val budget = pageNumberButtonBudget(
            slotWidth = 1200.dp,
            lastPage = 12,
            fontScale = 1f,
            fontSizeSp = 14f,
        )
        assertEquals(12, budget)
    }

    @Test
    fun aNarrowStripCollapsesToThePhoneFloor() {
        val budget = pageNumberButtonBudget(
            slotWidth = 100.dp,
            lastPage = 12,
            fontScale = 1f,
            fontSizeSp = 14f,
        )
        assertEquals(PAGE_NUMBER_BUDGET, budget)
    }

    @Test
    fun anUnmeasuredStripFallsBackToThePhoneFloor() {
        val budget = pageNumberButtonBudget(
            slotWidth = 0.dp,
            lastPage = 12,
            fontScale = 1f,
            fontSizeSp = 14f,
        )
        assertEquals(PAGE_NUMBER_BUDGET, budget)
    }

    @Test
    fun aLargerFontScaleStillFitsEveryPageOnAWideStrip() {
        val budget = pageNumberButtonBudget(
            slotWidth = 1600.dp,
            lastPage = 12,
            fontScale = 2f,
            fontSizeSp = 14f,
        )
        assertEquals(12, budget)
    }

    @Test
    fun aSinglePageTitleHasExactlyOneBudget() {
        val budget = pageNumberButtonBudget(
            slotWidth = 1200.dp,
            lastPage = 1,
            fontScale = 1f,
            fontSizeSp = 14f,
        )
        assertEquals(1, budget)
    }

    @Test
    fun aFourPageTitleFitsEveryPageOnAPlainPhoneStrip() {
        // Four 1-digit buttons + margins fit a ~461dp phone strip's numbers slot,
        // so a four-page title shows every page (no ellipsis → no Go-to-page).
        val budget = pageNumberButtonBudget(
            slotWidth = 260.dp,
            lastPage = 4,
            fontScale = 1f,
            fontSizeSp = 14f,
        )
        assertEquals(4, budget)
    }
}
