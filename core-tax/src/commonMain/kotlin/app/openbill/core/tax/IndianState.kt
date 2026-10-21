package app.openbill.core.tax

enum class IndianState(
    val code: String,
    val displayName: String,
) {
    JAMMU_AND_KASHMIR("01", "Jammu and Kashmir"),
    HIMACHAL_PRADESH("02", "Himachal Pradesh"),
    PUNJAB("03", "Punjab"),
    CHANDIGARH("04", "Chandigarh"),
    UTTARAKHAND("05", "Uttarakhand"),
    HARYANA("06", "Haryana"),
    DELHI("07", "Delhi"),
    RAJASTHAN("08", "Rajasthan"),
    UTTAR_PRADESH("09", "Uttar Pradesh"),
    BIHAR("10", "Bihar"),
    SIKKIM("11", "Sikkim"),
    ARUNACHAL_PRADESH("12", "Arunachal Pradesh"),
    NAGALAND("13", "Nagaland"),
    MANIPUR("14", "Manipur"),
    MIZORAM("15", "Mizoram"),
    TRIPURA("16", "Tripura"),
    MEGHALAYA("17", "Meghalaya"),
    ASSAM("18", "Assam"),
    WEST_BENGAL("19", "West Bengal"),
    JHARKHAND("20", "Jharkhand"),
    ODISHA("21", "Odisha"),
    CHHATTISGARH("22", "Chhattisgarh"),
    MADHYA_PRADESH("23", "Madhya Pradesh"),
    GUJARAT("24", "Gujarat"),
    DAMAN_AND_DIU_LEGACY("25", "Daman and Diu"),
    DADRA_AND_NAGAR_HAVELI_AND_DAMAN_AND_DIU(
        "26",
        "Dadra and Nagar Haveli and Daman and Diu",
    ),
    MAHARASHTRA("27", "Maharashtra"),
    ANDHRA_PRADESH_LEGACY("28", "Andhra Pradesh"),
    KARNATAKA("29", "Karnataka"),
    GOA("30", "Goa"),
    LAKSHADWEEP("31", "Lakshadweep"),
    KERALA("32", "Kerala"),
    TAMIL_NADU("33", "Tamil Nadu"),
    PUDUCHERRY("34", "Puducherry"),
    ANDAMAN_AND_NICOBAR_ISLANDS("35", "Andaman and Nicobar Islands"),
    TELANGANA("36", "Telangana"),
    ANDHRA_PRADESH("37", "Andhra Pradesh"),
    LADAKH("38", "Ladakh"),
    OTHER_TERRITORY("97", "Other Territory"),
    CENTRE_JURISDICTION("99", "Centre Jurisdiction"),
    ;

    companion object {
        fun fromCode(code: String): IndianState? =
            entries.firstOrNull { state -> state.code == code }
    }
}
