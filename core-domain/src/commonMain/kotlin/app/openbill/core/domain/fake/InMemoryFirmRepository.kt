package app.openbill.core.domain.fake

import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.repository.FirmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

class InMemoryFirmRepository : FirmRepository {
    private val firms = MutableStateFlow<List<Firm>>(emptyList())
    private val settings = MutableStateFlow<List<FirmSettings>>(emptyList())
    private val financialYears = MutableStateFlow<List<FinancialYear>>(emptyList())

    override fun observeFirms(): Flow<List<Firm>> =
        firms.map { values ->
            values.filter { it.deletedAt == null }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

    override suspend fun findFirm(id: String): Firm? = firms.value.firstOrNull { it.id == id }

    override suspend fun saveFirm(firm: Firm) {
        firms.update { current -> current.filterNot { it.id == firm.id } + firm }
    }

    override suspend fun archiveFirm(id: String, at: Instant) {
        firms.update { current ->
            current.map { firm ->
                if (firm.id == id && firm.deletedAt == null) {
                    firm.copy(deletedAt = at, updatedAt = at)
                } else {
                    firm
                }
            }
        }
    }

    override fun observeSettings(firmId: String): Flow<FirmSettings?> =
        settings.map { values -> values.firstOrNull { it.firmId == firmId } }

    override suspend fun findSettings(firmId: String): FirmSettings? =
        settings.value.firstOrNull { it.firmId == firmId }

    override suspend fun saveSettings(settings: FirmSettings) {
        this.settings.update { current ->
            current.filterNot { it.firmId == settings.firmId } + settings
        }
    }

    override fun observeFinancialYears(firmId: String): Flow<List<FinancialYear>> =
        financialYears.map { values ->
            values.filter { it.firmId == firmId }.sortedByDescending { it.startDate }
        }

    override suspend fun findFinancialYear(id: String): FinancialYear? =
        financialYears.value.firstOrNull { it.id == id }

    override suspend fun saveFinancialYear(financialYear: FinancialYear) {
        financialYears.update { current ->
            current.filterNot { it.id == financialYear.id } + financialYear
        }
    }
}
