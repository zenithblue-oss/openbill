package app.openbill.core.tax

sealed interface GstinValidation {
    data class Valid(val gstin: Gstin) : GstinValidation

    data class Invalid(val reason: GstinError) : GstinValidation
}
