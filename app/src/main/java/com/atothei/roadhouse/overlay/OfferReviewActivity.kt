package com.atothei.roadhouse.overlay

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Manual review list for seen offers: every offer still UNKNOWN, oldest
 * first, each with its own Took it / Skipped it buttons. Marking one
 * updates seen_offers.json and removes the row from the list. Offers
 * flagged needsReview (identical offers the pay matcher could not tell
 * apart) are listed first and labelled, so the ambiguous ones are the
 * first thing he sees. Reached from the MainActivity "Review offers"
 * button, or by voice ("review my offers").
 */
class OfferReviewActivity : AppCompatActivity() {

    private lateinit var listLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "Review offers"
            textSize = 22f
        }
        val hint = TextView(this).apply {
            text = "Offers the overlay saw but couldn't resolve. " +
                "Mark each one took or skipped. Identical offers are listed first — " +
                "only you can tell them apart."
            textSize = 15f
            setPadding(0, 16, 0, 24)
        }
        listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(title)
        root.addView(hint)
        root.addView(listLayout)
        scroll.addView(root)
        setContentView(scroll)
        refreshList()
    }

    private fun refreshList() {
        listLayout.removeAllViews()
        val unresolved = SeenOffers.unresolved(this)
        if (unresolved.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "Nothing to review — every seen offer is resolved."
                textSize = 16f
            })
            return
        }
        val sorted = unresolved.sortedWith(
            compareByDescending<SeenOffer> { it.needsReview }.thenBy { it.createdAtMs }
        )
        val summary = TextView(this).apply {
            val flagged = sorted.count { it.needsReview }
            text = "${sorted.size} offer${if (sorted.size == 1) "" else "s"} waiting" +
                if (flagged > 0) " ($flagged need your call — identical offers)" else ""
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        listLayout.addView(summary)
        for (offer in sorted) {
            listLayout.addView(rowFor(offer))
        }
    }

    private fun rowFor(offer: SeenOffer): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }
        val platform = Platforms.displayFromTag(offer.platform)
        val payPart = if (offer.pay != null) "$%.2f".format(offer.pay) else "unknown pay"
        val milesPart = if (offer.miles != null) "%.1f mi".format(offer.miles) else "unknown miles"
        val label = offer.storeLabel.ifBlank { platform }
        val line = TextView(this).apply {
            text = "$platform — $label\n$payPart, $milesPart · ${offer.date} ${offer.time}" +
                if (offer.needsReview) "\nNeeds your call — identical to another offer." else ""
            textSize = 16f
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val took = Button(this).apply {
            text = "Took it"
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = 8 }
            setOnClickListener { mark(offer, OfferOutcome.TOOK) }
        }
        val skipped = Button(this).apply {
            text = "Skipped it"
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 8 }
            setOnClickListener { mark(offer, OfferOutcome.SKIPPED) }
        }
        buttons.addView(took)
        buttons.addView(skipped)
        row.addView(line)
        row.addView(buttons)
        return row
    }

    private fun mark(offer: SeenOffer, outcome: OfferOutcome) {
        val ok = SeenOffers.markOutcome(this, offer.id, outcome)
        Toast.makeText(
            this,
            if (ok) "Recorded: ${if (outcome == OfferOutcome.TOOK) "took it" else "skipped it"}."
            else "Couldn't save that. Try again.",
            Toast.LENGTH_SHORT
        ).show()
        refreshList()
    }
}
