package app.openbill.ui.pdf

/** Language of the printed labels (headings, column titles, copy labels); party and item text prints as entered. */
enum class InvoiceLanguage(val label: String) {
    ENGLISH("English"),
    HINDI("हिन्दी (Hindi)"),
    ;

    /** [english] in this language; labels without a translation, and anything that is not a label, stay as they are. */
    fun t(english: String): String = if (this == ENGLISH) english else HINDI_LABELS[english.lowercase()] ?: english

    companion object {
        fun fromName(name: String?): InvoiceLanguage = entries.firstOrNull { it.name == name } ?: ENGLISH
    }
}

/**
 * Keys are the lower-cased English labels used by the templates; GST terms stay recognisable (GSTIN, HSN/SAC, CGST).
 * Avoid reph + i-matra clusters such as "र्ति": the PDFBox Devanagari shaper misorders them.
 */
private val HINDI_LABELS: Map<String, String> = mapOf(
    // Document titles and copy labels
    "tax invoice" to "कर बीजक",
    "invoice" to "बीजक",
    "purchase bill" to "खरीद बिल",
    "credit note" to "क्रेडिट नोट",
    "debit note" to "डेबिट नोट",
    "quotation" to "कोटेशन",
    "proforma invoice" to "प्रोफ़ॉर्मा बीजक",
    "delivery challan" to "डिलीवरी चालान",
    "sales order" to "बिक्री आदेश",
    "purchase order" to "खरीद आदेश",
    "original for recipient" to "प्राप्तकर्ता के लिए मूल प्रति",
    "duplicate for transporter" to "परिवहनकर्ता के लिए दूसरी प्रति",
    "triplicate for supplier" to "सप्लायर के लिए तीसरी प्रति",
    // Parties and document details
    "bill to" to "बिल प्राप्तकर्ता",
    "billed to" to "बिल प्राप्तकर्ता",
    "ship to" to "माल भेजें",
    "shipped to" to "माल भेजा गया",
    "same as billing address" to "बिलिंग पते के समान",
    "cash sale" to "नकद बिक्री",
    "cash sale (no party)" to "नकद बिक्री",
    "party" to "पार्टी",
    "address" to "पता",
    "phone" to "फ़ोन",
    "invoice no." to "बीजक संख्या",
    "bill no." to "बिल संख्या",
    "invoice date" to "बीजक तिथि",
    "date" to "तिथि",
    "issued" to "जारी",
    "due date" to "देय तिथि",
    "due" to "देय",
    "place of supply" to "सप्लाई का स्थान",
    "place" to "स्थान",
    "supply" to "सप्लाई",
    "supply details" to "सप्लाई विवरण",
    "supply type" to "सप्लाई का प्रकार",
    "type" to "प्रकार",
    "intra-state" to "राज्य के भीतर",
    "inter-state" to "अंतर-राज्यीय",
    "reverse charge" to "रिवर्स चार्ज",
    "tax payable on reverse charge" to "रिवर्स चार्ज पर देय कर",
    "yes" to "हाँ",
    "no" to "नहीं",
    "details" to "विवरण",
    "document details" to "दस्तावेज़ विवरण",
    // Item table
    "item" to "वस्तु",
    "items" to "वस्तुएँ",
    "particulars" to "विवरण",
    "qty" to "मात्रा",
    "total qty" to "कुल मात्रा",
    "rate" to "दर",
    "discount" to "छूट",
    "disc." to "छूट",
    "gst rate" to "GST दर",
    "tax" to "कर",
    "amount" to "राशि",
    "taxable value" to "कर योग्य मूल्य",
    "total tax" to "कुल कर",
    "total" to "कुल",
    "invoice total" to "बीजक कुल",
    "cess" to "उपकर",
    "gst summary" to "GST सारांश",
    "hsn/sac summary" to "HSN/SAC सारांश",
    // Footer blocks
    "amount in words" to "राशि शब्दों में",
    "party balance" to "पार्टी शेष",
    "notes" to "टिप्पणी",
    "terms and conditions" to "नियम और शर्तें",
    "payment details" to "भुगतान विवरण",
    "scan to pay" to "भुगतान के लिए स्कैन करें",
    "scan to pay with upi" to "UPI से भुगतान के लिए स्कैन करें",
    "upi id" to "UPI आईडी",
    "authorised signatory" to "अधिकृत हस्ताक्षरकर्ता",
    "computer-generated invoice" to "कंप्यूटर द्वारा बनाया गया बीजक",
    "continued on next page" to "अगले पृष्ठ पर जारी",
    "thank you for your purchase." to "खरीदारी के लिए धन्यवाद।",
    "continued" to "जारी",
    "supplier" to "सप्लायर",
    "receiver's signature" to "प्राप्तकर्ता के हस्ताक्षर",
    "items taxable value" to "वस्तुओं का कर योग्य मूल्य",
    "no." to "संख्या",
    "state" to "राज्य",
    // Totals and bank rows
    "total taxable value" to "कुल कर योग्य मूल्य",
    "round off" to "पूर्णांकन",
    "less: gst on reverse charge" to "घटाएँ: रिवर्स चार्ज पर GST",
    "bank" to "बैंक",
    "a/c no." to "खाता संख्या",
    "branch" to "शाखा",
    // Transport and order details
    "transporter" to "परिवहनकर्ता",
    "transporter id" to "परिवहनकर्ता आईडी",
    "transport mode" to "परिवहन का साधन",
    "vehicle no." to "वाहन संख्या",
    "lr/doc no." to "LR/दस्तावेज़ संख्या",
    "po no." to "PO संख्या",
    "po date" to "PO तिथि",
    "e-way bill no." to "ई-वे बिल संख्या",
)
