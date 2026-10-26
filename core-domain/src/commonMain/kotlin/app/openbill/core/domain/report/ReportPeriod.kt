package app.openbill.core.domain.report

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Inclusive date range of a report. */
data class ReportPeriod(val from: LocalDate, val to: LocalDate) {
    init {
        require(from <= to) { "Report start $from is after its end $to" }
    }

    /** GSTN return period "MMYYYY" of a single-month range. */
    val returnPeriod: String
        get() {
            require(from.year == to.year && from.month == to.month) { "A return period is one calendar month" }
            return from.month.ordinal.plus(1).toString().padStart(2, '0') + from.year
        }

    companion object {
        /** "2026-09" -> 1 Sep to 30 Sep 2026. */
        fun month(period: String): ReportPeriod {
            val m = Regex("""^(\d{4})-(\d{2})$""").matchEntire(period.trim())
                ?: throw IllegalArgumentException("Period must be YYYY-MM: $period")
            return monthOf(LocalDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 1))
        }

        fun monthOf(date: LocalDate): ReportPeriod {
            val first = LocalDate(date.year, date.month, 1)
            return ReportPeriod(first, first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)))
        }

        /** The financial year containing [date]; [startMonth] 4 = April-March. */
        fun financialYearOf(date: LocalDate, startMonth: Int = 4): ReportPeriod {
            val startYear = if (date.month.ordinal + 1 >= startMonth) date.year else date.year - 1
            val start = LocalDate(startYear, startMonth, 1)
            return ReportPeriod(start, start.plus(DatePeriod(years = 1)).minus(DatePeriod(days = 1)))
        }

        /** The financial-year quarter containing [date]. */
        fun quarterOf(date: LocalDate, startMonth: Int = 4): ReportPeriod {
            val fy = financialYearOf(date, startMonth).from
            var start = fy
            while (start.plus(DatePeriod(months = 3)) <= date) start = start.plus(DatePeriod(months = 3))
            return ReportPeriod(start, start.plus(DatePeriod(months = 3)).minus(DatePeriod(days = 1)))
        }
    }
}

/** Date-range presets offered by report screens. */
enum class PeriodPreset(val label: String) {
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    THIS_QUARTER("This quarter"),
    THIS_FY("This FY"),
    LAST_FY("Last FY"),
    ;

    fun resolve(today: LocalDate, startMonth: Int = 4): ReportPeriod = when (this) {
        THIS_MONTH -> ReportPeriod.monthOf(today)
        LAST_MONTH -> ReportPeriod.monthOf(today.minus(DatePeriod(months = 1)))
        THIS_QUARTER -> ReportPeriod.quarterOf(today, startMonth)
        THIS_FY -> ReportPeriod.financialYearOf(today, startMonth)
        LAST_FY -> ReportPeriod.financialYearOf(today.minus(DatePeriod(years = 1)), startMonth)
    }
}
