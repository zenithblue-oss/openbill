package app.openbill.core.tax

enum class GstinError {
    LENGTH,
    CHARACTERS,
    STATE_CODE,
    PAN_FORMAT,
    ENTITY_CODE,
    DEFAULT_Z,
    CHECKSUM,
}
