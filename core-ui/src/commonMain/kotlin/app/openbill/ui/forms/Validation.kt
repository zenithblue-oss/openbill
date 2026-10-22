package app.openbill.ui.forms

import app.openbill.core.tax.Gstin
import app.openbill.core.tax.GstinError
import app.openbill.core.tax.GstinValidation
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.Parse

object Validation {
    /** Returns an error message, or null when the GSTIN is blank (optional) or valid. */
    fun gstinError(raw: String): String? {
        if (raw.isBlank()) return null
        return when (val result = Gstin.validate(raw)) {
            is GstinValidation.Valid -> null
            is GstinValidation.Invalid -> when (result.reason) {
                GstinError.LENGTH -> "GSTIN must be 15 characters"
                GstinError.CHARACTERS -> "Use only letters and digits"
                GstinError.STATE_CODE -> "First two digits are not a valid state code"
                GstinError.PAN_FORMAT -> "Characters 3-12 must be a valid PAN"
                GstinError.ENTITY_CODE -> "13th character is invalid"
                GstinError.DEFAULT_Z -> "14th character must be Z"
                GstinError.CHECKSUM -> "Check digit does not match - please re-check the GSTIN"
            }
        }
    }

    /** State code implied by a valid GSTIN, or null. */
    fun stateFromGstin(raw: String): String? =
        (Gstin.validate(raw) as? GstinValidation.Valid)?.gstin?.stateCode?.code

    /**
     * Keeps the state in step with the GSTIN. Returns the state to show and whether it is still
     * GSTIN-derived. A state the user picked by hand is never overwritten; a derived (or empty)
     * state follows the GSTIN whenever it becomes valid.
     */
    fun syncStateWithGstin(
        oldState: String?,
        newState: String?,
        derived: Boolean,
        gstin: String,
    ): Pair<String?, Boolean> {
        val pickedByHand = newState != oldState
        if (pickedByHand) return newState to (newState != null && newState == stateFromGstin(gstin))
        val implied = stateFromGstin(gstin) ?: return newState to derived
        return if (newState == null || derived) implied to true else newState to false
    }

    fun normalizedGstin(raw: String): String? = raw.takeIf { it.isNotBlank() }?.let { Gstin.normalize(it) }

    fun gstinStateMismatch(gstin: String, stateCode: String?): String? {
        val fromGstin = stateFromGstin(gstin) ?: return null
        return if (stateCode != null && stateCode != fromGstin) {
            "This GSTIN is registered in ${Fmt.stateName(fromGstin)}, but ${Fmt.stateName(stateCode)} is selected"
        } else {
            null
        }
    }

    fun moneyError(text: String, required: Boolean = false): String? {
        if (text.isBlank()) return if (required) "Required" else null
        val v = Parse.money(text) ?: return "Enter an amount like 1250 or 99.50"
        return if (v.paise < 0) "Cannot be negative" else null
    }

    fun phoneError(text: String): String? {
        if (text.isBlank()) return null
        val digits = text.filter { it.isDigit() }
        return if (digits.length < 10 || digits.length > 13) "Enter a 10-digit mobile or a landline with STD code" else null
    }

    fun hsnError(text: String): String? {
        if (text.isBlank()) return null
        val t = text.trim()
        return if (t.all { it.isDigit() } && t.length in 4..8) null else "HSN/SAC is 4 to 8 digits"
    }
}
