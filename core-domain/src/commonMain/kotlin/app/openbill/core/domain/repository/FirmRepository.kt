package app.openbill.core.domain.repository

import app.openbill.core.domain.model.FinancialYear
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface FirmRepository {
    fun observeFirms(): Flow<List<Firm>>

    suspend fun findFirm(id: String): Firm?

    suspend fun saveFirm(firm: Firm)

    suspend fun archiveFirm(id: String, at: Instant)

    fun observeSettings(firmId: String): Flow<FirmSettings?>

    suspend fun findSettings(firmId: String): FirmSettings?

    suspend fun saveSettings(settings: FirmSettings)

    fun observeFinancialYears(firmId: String): Flow<List<FinancialYear>>

    suspend fun findFinancialYear(id: String): FinancialYear?

    suspend fun saveFinancialYear(financialYear: FinancialYear)
}
