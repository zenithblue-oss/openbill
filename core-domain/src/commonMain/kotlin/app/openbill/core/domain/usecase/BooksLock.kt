package app.openbill.core.domain.usecase

import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.repository.FirmRepository
import app.openbill.core.domain.repository.VoucherRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Books lock and financial-year close. A closed year (or a lock date) blocks posting,
 * cancelling and restoring vouchers dated inside it; balances carry forward on their own
 * because the ledger is continuous. Closing is reversible until a voucher dated after the
 * year is posted following the close.
 */
class BooksLock(
    private val firms: FirmRepository,
    private val vouchers: VoucherRepository,
    private val newId: () -> String,
) {
    suspend fun setLockDate(firmId: String, date: LocalDate?): FirmSettings {
        val settings = firms.findSettings(firmId) ?: FirmSettings(firmId = firmId)
        return settings.copy(lockDate = date).also { firms.saveSettings(it) }
    }

    /** Closes [yearId] and makes sure the following year exists. Returns the closed year. */
    suspend fun closeYear(yearId: String, at: Instant): FinancialYear {
        val year = firms.findFinancialYear(yearId) ?: throw IllegalArgumentException("Unknown financial year: $yearId")
        require(year.closedAt == null) { "This financial year is already closed" }
        val years = firms.observeFinancialYears(year.firmId).first()
        require(years.none { it.closedAt == null && it.endDate < year.startDate }) { "Close earlier financial years first" }
        val nextStart = year.endDate.plus(DatePeriod(days = 1))
        if (years.none { nextStart >= it.startDate && nextStart <= it.endDate }) {
            firms.saveFinancialYear(
                FinancialYear(newId(), year.firmId, nextStart, nextStart.plus(DatePeriod(years = 1)).minus(DatePeriod(days = 1))),
            )
        }
        return year.copy(closedAt = at).also { firms.saveFinancialYear(it) }
    }

    /** Reopens a closed year while no later-dated voucher has been posted since it was closed. */
    suspend fun reopenYear(yearId: String): FinancialYear {
        val year = firms.findFinancialYear(yearId) ?: throw IllegalArgumentException("Unknown financial year: $yearId")
        val closedAt = year.closedAt ?: throw IllegalArgumentException("This financial year is not closed")
        val years = firms.observeFinancialYears(year.firmId).first()
        require(years.none { it.closedAt != null && it.startDate > year.endDate }) { "Reopen later financial years first" }
        val later = vouchers.observeVouchers(year.firmId, year.endDate.plus(DatePeriod(days = 1)), LocalDate(9999, 12, 31)).first()
        require(later.none { it.status != VoucherStatus.DRAFT && (it.postedAt ?: closedAt) > closedAt }) {
            "A voucher of the next year was posted after closing; the year can no longer be reopened"
        }
        return year.copy(closedAt = null).also { firms.saveFinancialYear(it) }
    }
}
