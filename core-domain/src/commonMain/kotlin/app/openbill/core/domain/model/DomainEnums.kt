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
    /** Goods sent without a sale; no ledger or stock posting. */
    DELIVERY_CHALLAN,
    /** Transfer between two cash/bank accounts. */
    CONTRA,
    /** Customer order; no stock or ledger effect until converted to an invoice. */
    SALES_ORDER,
    /** Order to a supplier; no stock or ledger effect until converted to a purchase bill. */
    PURCHASE_ORDER,
}

/** How much of an issued order has been converted into posted invoices or bills. */
enum class OrderFulfilment {
    NONE,
    PARTIAL,
    COMPLETE,
}

enum class VoucherStatus {
    DRAFT,
    POSTED,
    CANCELLED,
}

enum class StockDirection {
    INWARD,
    OUTWARD,
}
