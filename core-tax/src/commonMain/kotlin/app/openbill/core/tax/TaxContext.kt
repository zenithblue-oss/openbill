package app.openbill.core.tax

data class TaxContext(
    val firmStateCode: String,
    val placeOfSupplyStateCode: String,
) {
    init {
        require(isStateCode(firmStateCode)) {
            "firmStateCode must be exactly two ASCII digits"
        }
        require(isStateCode(placeOfSupplyStateCode)) {
            "placeOfSupplyStateCode must be exactly two ASCII digits"
        }
    }

    val isIntraState: Boolean
        get() = firmStateCode == placeOfSupplyStateCode

    private fun isStateCode(code: String): Boolean =
        code.length == 2 && code[0] in '0'..'9' && code[1] in '0'..'9'
}
