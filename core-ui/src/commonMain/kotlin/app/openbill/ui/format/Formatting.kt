package app.openbill.ui.format

import app.openbill.core.model.Money
import app.openbill.core.model.Quantity
import app.openbill.core.model.Rate
import app.openbill.core.tax.IndianState
import kotlinx.datetime.LocalDate

/** Formatting and parsing helpers shared by every screen and by the PDF renderer. */
object Fmt {
    /** Indian digit grouping (12,34,567.89) with a rupee sign. */
    fun inr(money: Money, symbol: Boolean = true): String {
        val negative = money.paise < 0
        val abs = if (negative) -money.paise else money.paise
        val rupees = abs / 100
        val paise = abs % 100
        val grouped = groupIndian(rupees.toString())
        val body = grouped + "." + paise.toString().padStart(2, '0')
        val prefix = if (symbol) "₹" else ""
        return (if (negative) "-" else "") + prefix + body
    }

    fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        var rest = digits.dropLast(3)
        val parts = ArrayDeque<String>()
        while (rest.length > 2) {
            parts.addFirst(rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.addFirst(rest)
        return parts.joinToString(",") + "," + last3
    }

    /** Rate as a percentage string, e.g. 18, 0.25, 12.5. */
    fun percent(rate: Rate): String {
        val whole = rate.units / 10_000
        val frac = (rate.units % 10_000).toString().padStart(4, '0').trimEnd('0')
        return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    }

    fun qty(quantity: Quantity): String = quantity.format()

    /** dd-MM-yyyy for display. */
    fun date(date: LocalDate): String {
        val parts = date.toString().split("-")
        return if (parts.size == 3) "${parts[2]}-${parts[1]}-${parts[0]}" else date.toString()
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "22 Oct 2026": unambiguous for pickers and headers. */
    fun dateLong(date: LocalDate): String {
        val month = date.toString().substring(5, 7).toInt()
        return "${date.toString().substring(8, 10).trimStart('0')} ${MONTHS[month - 1]} ${date.year}"
    }

    /** Signed amount for deductions: "−₹50.00" (true minus sign), or "₹0.00" when zero. */
    fun inrDeduction(money: Money): String = if (money.paise == 0L) inr(money) else "\u2212" + inr(money)

    /** Signed adjustment such as round-off: "−₹0.36" (true minus sign) or "₹0.64". */
    fun inrSigned(money: Money): String = if (money.paise < 0) "\u2212" + inr(-money) else inr(money)

    /** Quantity with its unit symbol: "2.5 KG", "12 NOS", or just "3" without a unit. */
    fun qtyUnit(quantity: Quantity, unit: String?): String =
        qty(quantity) + (unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

    fun stateName(code: String?): String =
        code?.let { IndianState.fromCode(it)?.let { s -> "${s.displayName} (${s.code})" } ?: it } ?: "-"

    fun financialYearLabel(startYear: Int): String =
        "FY $startYear-${(startYear + 1).toString().takeLast(2)}"
}

/** Conversions between [LocalDate] and the UTC-midnight milliseconds the Material date picker uses. */
object PickerDates {
    private const val DAY_MS = 86_400_000L

    fun toUtcMillis(date: LocalDate): Long = date.toEpochDays().toLong() * DAY_MS

    fun fromUtcMillis(millis: Long): LocalDate = LocalDate.fromEpochDays(millis.floorDiv(DAY_MS))
}

/** Lenient parsers that return null instead of throwing. */
object Parse {
    fun money(text: String): Money? {
        val t = text.trim().replace(",", "")
        if (t.isEmpty()) return Money.ZERO
        return runCatching { Money.parse(t) }.getOrNull()
    }

    fun quantity(text: String): Quantity? {
        val t = text.trim().replace(",", "")
        if (t.isEmpty()) return null
        return runCatching { Quantity.parse(t) }.getOrNull()
    }

    /** Parses "18" or "0.25" (percent) into a [Rate]. */
    fun percent(text: String): Rate? {
        val t = text.trim().removeSuffix("%").trim()
        if (t.isEmpty()) return Rate.ofBasisPoints(0)
        // Money.parse gives hundredths, which for a percentage are basis points.
        val bp = runCatching { Money.parse(t).paise }.getOrNull() ?: return null
        if (bp < 0) return null
        return Rate.ofBasisPoints(bp)
    }

    fun date(text: String): LocalDate? {
        val t = text.trim()
        runCatching { return LocalDate.parse(t) }
        val parts = t.split("-", "/", ".")
        if (parts.size == 3 && parts[2].length == 4) {
            val d = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val y = parts[2].toIntOrNull() ?: return null
            return runCatching { LocalDate(y, m, d) }.getOrNull()
        }
        return null
    }
}

/** Converts a rupee amount to Indian-English words, e.g. "One Lakh Twenty Rupees and Five Paise Only". */
object AmountInWords {
    private val ones = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
        "Eighteen", "Nineteen",
    )
    private val tens = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety",
    )

    fun rupees(money: Money): String {
        val abs = if (money.paise < 0) -money.paise else money.paise
        val rupees = abs / 100
        val paise = (abs % 100).toInt()
        val r = if (rupees == 0L) "Zero" else words(rupees)
        val p = if (paise > 0) " and ${belowHundred(paise)} Paise" else ""
        return "${if (money.paise < 0) "Minus " else ""}$r Rupees$p Only"
    }

    fun words(n: Long): String {
        require(n >= 0)
        if (n == 0L) return "Zero"
        val parts = mutableListOf<String>()
        var rest = n
        val crore = rest / 10_000_000
        rest %= 10_000_000
        val lakh = rest / 100_000
        rest %= 100_000
        val thousand = rest / 1_000
        rest %= 1_000
        val hundred = rest / 100
        rest %= 100
        if (crore > 0) parts += "${words(crore)} Crore"
        if (lakh > 0) parts += "${belowHundred(lakh.toInt())} Lakh"
        if (thousand > 0) parts += "${belowHundred(thousand.toInt())} Thousand"
        if (hundred > 0) parts += "${ones[hundred.toInt()]} Hundred"
        if (rest > 0) parts += belowHundred(rest.toInt())
        return parts.joinToString(" ")
    }

    private fun belowHundred(n: Int): String = when {
        n < 20 -> ones[n]
        n % 10 == 0 -> tens[n / 10]
        else -> "${tens[n / 10]} ${ones[n % 10]}"
    }
}
