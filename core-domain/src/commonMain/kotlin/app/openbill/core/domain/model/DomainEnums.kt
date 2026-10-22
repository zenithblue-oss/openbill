package app.openbill.core.domain.model

enum class PartyType {
    CUSTOMER,
    SUPPLIER,
    BOTH,
}

enum class AddressKind {
    BILLING,
    SHIPPING,
    OTHER,
}

enum class OpeningBalanceType {
    DEBIT,
    CREDIT,
}

enum class VoucherType {
    SALE,
    PURCHASE,
    CREDIT_NOTE,
    DEBIT_NOTE,
    QUOTATION,
    PROFORMA,
    PAYMENT_IN,
    PAYMENT_OUT,
    EXPENSE,
    STOCK_ADJUSTMENT,
    STOCK_TRANSFER,
}

enum class VoucherStatus {
    DRAFT,
    POSTED,
    CANCELLED,
}
