# Glossary

This glossary defines terms used in OpenBill's product, tax, inventory, and engineering
documentation. Entries are alphabetical and describe how OpenBill uses each term; they are
not a substitute for the underlying statute.

Tax rates, thresholds, and rules change. OpenBill stores tax rates and rule sets
as versioned data with effective dates, so a posted document keeps the values that applied
when it was posted. Any rate in an example is illustrative only.

This is product documentation, not legal, tax, or accounting advice. Confirm current
obligations with a qualified professional and the authority that administers them.

**A4/A5/thermal printing.** Sheet layouts for PDF and office printers, plus 58 mm and
80 mm roll layouts for thermal receipt printers.

**aggregate turnover.** The total value of taxable, exempt, export, and other supplies
across a GSTIN. OpenBill computes a working figure, not an official determination.

**aging.** Grouping unpaid balances by days overdue, commonly 0-30, 31-60, 61-90, and 90+;
buckets are configurable.

**alternate unit.** A second unit for an item with a fixed conversion to its base unit,
such as a box of twelve pieces.

**assessment year.** The income-tax year following the financial year in which income was
earned. OpenBill uses the financial year for billing and GST.

**audit trail.** An append-only record of who changed a record, when, and why. Posted
vouchers are never silently rewritten.

**B2B.** A supply to a GST-registered business recipient; the invoice carries the
recipient's GSTIN and is reported invoice-wise.

**B2C.** A supply to an unregistered consumer, generally summarized in returns subject to
the large B2C rule.

**B2C large.** An inter-state B2C supply above the reporting threshold, requiring
invoice-level details in GSTR-1; the threshold is versioned.

**backup.** A portable archive of a database snapshot, attachments, and a manifest with
versions and checksums.

**barcode.** A machine-readable symbol, such as Code 128 or QR, encoding an item, SKU,
batch, or serial.

**batch.** A supplier or manufacturer lot number for stock, tracked through movements and
optionally linked to expiry.

**bill of supply.** A non-tax document used when no GST is charged, such as by a
composition dealer or for exempt supplies.

**cess.** An additional levy on notified goods, calculated on taxable value and shown
separately from GST; coverage and rates are versioned.

**CGST.** Central GST on an intra-state supply, charged at half the GST rate. CGST + SGST
equals the full rate; an illustrative 18 percent rate is 9 percent CGST plus 9 percent SGST.

**closing balance.** The balance at the end of a period, carried forward as the next
opening balance.

**Compose Multiplatform.** JetBrains' declarative Kotlin UI framework, used by OpenBill to
share Android and desktop screens.

**composition scheme.** A simplified scheme for eligible small taxpayers with a flat
turnover rate, no input tax credit, and a bill of supply instead of a tax invoice.

**credit note.** A supplier document that reduces the value or tax of an earlier invoice,
such as for a sales return or correction.

**day book.** A chronological list of vouchers posted on each date, with type, number,
party, and amount.

**debit note.** A document that increases the amount payable or receivable under an earlier
transaction, such as a purchase return.

**delivery challan.** A document accompanying goods moved without a sale, such as a stock
transfer or job work; it is not a payment demand.

**e-invoice.** An invoice registered with the Invoice Registration Portal for notified
businesses, returning an IRN and signed QR; OpenBill uses the user's own credentials.

**e-way bill.** An electronic consignment document for specified goods movements, generated
through the official portal or an approved integration using user credentials.

**exempt supply.** A supply not liable to GST, so no tax is charged; input tax credit is
generally unavailable.

**expiry.** The date after which a batch should not be sold. OpenBill stores it per batch
and warns during billing.

**financial year.** The Indian business year from 1 April to 31 March, used for numbering,
reports, and year-end close.

**financial-year close.** The controlled close of a financial year: locking prior
postings, carrying balances forward, and preserving reports. Reversible until the first
posting in the new year.

**FTS (full-text search).** Local ranked search using SQLite FTS5, with highlighted
matches kept in sync with source records. See
[docs/02-search-analysis.md](02-search-analysis.md).

**godown.** A physical stock location such as a shop, warehouse, or storage room; stock is
tracked per godown.

**GST.** Goods and Services Tax, a destination-based indirect tax on supplies in India,
with credit generally available for business input tax.

**GST summary.** A report of output tax, eligible input tax credit, and net GST payable for
a period, grouped by tax head and rate.

**GSTIN.** The 15-character GST registration number, containing a state code, PAN, entity
code, default character, and checksum; OpenBill validates it offline.

**GSTR-1.** The GST return for outward supplies, reporting invoice-level B2B and large B2C
data. OpenBill exports it but does not file it.

**GSTR-3B.** A summary GST return of output tax, input tax credit, and net payable.
OpenBill prepares a working computation only.

**HSN.** Harmonized System of Nomenclature, the goods classification code. Required digit
length varies, and OpenBill stores it per item with offline lookup.

**HSN summary.** A report grouping sales or purchases by HSN or SAC with quantity, taxable
value, and tax for return preparation.

**idempotency.** The property that repeating an operation has no additional effect.
OpenBill uses keys so retries cannot post duplicate vouchers or payments.

**IGST.** Integrated GST on inter-state supplies, charged at the full GST rate, equivalent
to the CGST plus SGST of an intra-state supply.

**input tax credit.** Credit for GST paid on eligible business inputs, used to offset
output tax: net GST payable = output tax - eligible input tax credit. Conditions and
blocked credits are statutory rules.

**inter-state supply.** A supply where the supplier location and place of supply are in
different states or union territories; it attracts IGST.

**intra-state supply.** A supply where the supplier location and place of supply are in the
same state or union territory; it attracts CGST plus SGST, or CGST plus UTGST.

**invoice.** A commercial document recording a supply, with parties, items, quantities,
prices, taxes, and terms. Tax invoice and bill of supply are specific kinds.

**invoice numbering.** The rule assigning unique sequential numbers per document type and
financial year. OpenBill assigns at posting and retains cancelled numbers.

**IRN.** Invoice Reference Number, the 64-character hash returned on e-invoice registration
and encoded in the signed QR code.

**KMP (Kotlin Multiplatform).** OpenBill shares domain, database, tax, search, and
reporting code across Android and JVM desktop with KMP.

**ledger.** A chronological account of debits and credits with a running balance, kept for
parties, cash, bank, and other balances.

**minimum SDK (minSdk).** The lowest Android API level an app supports. OpenBill's floor is
API 26 (Android 8.0); compile and target API 36 at the time of writing, moving the target to
the latest stable API before release.

**minor units.** The smallest divisible unit of a currency. For INR it is the paisa, and
OpenBill stores money as integer minor units.

**MRP.** Maximum Retail Price, the tax-inclusive ceiling printed on packaged goods; it may
be higher than the price actually charged.

**MSME.** Micro, Small, or Medium Enterprise, classified by investment and turnover
thresholds. OpenBill stores the details but does not certify eligibility.

**nil-rated supply.** A taxable supply with a 0 percent GST rate. No tax is charged, but
input tax credit is generally available, unlike an exempt supply.

**opening balance.** The balance brought forward at the start of a period for a party,
account, item, or firm, posted explicitly.

**output tax.** GST charged on outward taxable supplies; a liability that eligible input
tax credit can reduce.

**outstanding.** The unpaid or unadjusted part of an invoice or bill, used in receivables,
payables, reminders, and aging.

**paise.** The minor unit of the Indian rupee; 100 paise equal 1 rupee, and OpenBill stores
amounts as integer paise.

**party ledger.** A dated statement of transactions with one customer or supplier, showing
debits, credits, and a running balance.

**payable.** An amount the business owes a supplier or other party, tracked per party and
summarized by aging.

**payment allocation.** The link between a payment and one or more invoices or bills.
Allocation reduces outstanding and cannot exceed either balance.

**place of supply.** The location deciding whether a supply is intra-state or
inter-state. Rules differ for goods and services; OpenBill records it per document.

**POS billing.** Point-of-sale billing: a fast counter workflow with barcode scanning,
quick payment capture, and a thermal receipt.

**proforma invoice.** A preliminary, non-posting document showing proposed goods, prices,
and terms. It is not a tax invoice or a demand for payment.

**purchase bill.** The recorded supplier invoice for goods or services bought. Posting
increases stock and payables and may create input tax credit.

**purchase price.** The price at which the business buys an item, used for purchase entry,
margins, and stock valuation.

**quotation.** A non-binding price offer to a prospective customer, convertible to an
invoice with the source link preserved.

**receivable.** An amount owed to the business by a customer, tracked per party and
summarized by aging.

**restore.** Two distinct operations. (a) Recycle-bin or cancelled-voucher restore returns a
soft-deleted draft or a cancelled voucher to active use; restoring a cancelled voucher
re-posts its stock and ledger effects in one transaction, keeps its original number, and
writes an audit event. (b) Backup restore replaces local data from a validated archive.
Cancelled-voucher restore rejects duplicates and requires the original cancellation reason
and a restore reason. See [docs/03-architecture.md](03-architecture.md).

**reverse charge.** A mechanism where the recipient pays GST instead of the supplier. The
invoice is flagged and the recipient accounts for the tax.

**round-off.** A small adjustment to bring a total to the nearest rupee, posted to a
rounding account so the ledger balances.

**SAC.** Services Accounting Code, the classification code for services, stored per service
item alongside HSN for goods.

**sale price.** The default price charged to customers, before or after tax according to
the item setting; party-specific prices may override it.

**serial number/IMEI.** A unique identifier for an individual unit, such as a phone IMEI,
tracked through purchase, sale, and return.

**SGST.** State GST on an intra-state supply, charged at the same rate as CGST; together
they equal the full GST rate.

**SKU.** Stock Keeping Unit, a unique internal code for an item or variant, used in search,
labels, and stock reports.

**soft delete.** Marking a record deleted without removing it: hidden from normal lists,
retained for audit, and restorable. Posted vouchers are never hard-deleted.

**stock adjustment.** A reasoned increase or decrease not caused by sale, purchase, or
transfer, such as damage or a count correction; it creates a stock movement.

**stock ledger.** An append-only record of stock movements by document, godown, batch,
quantity, and time. On-hand stock is derived, not edited.

**stock transfer.** Moving stock between godowns, creating paired outward and inward
movements without changing total firm stock.

**Tally export.** An export of vouchers and masters as Tally-compatible XML for accountant
import, with explicit document and ledger mapping.

**tax invoice.** A GST-compliant invoice for a taxable supply, with GSTINs, number and date,
HSN or SAC, taxable value, tax, and place of supply.

**taxable value.** The amount on which GST is calculated: generally transaction value less
eligible discounts plus taxable charges. GST = taxable value x tax rate.

**TCS.** Tax Collected at Source, collected by a seller and remitted to government. It is
recorded separately from GST output tax, with statutory thresholds.

**TDS.** Tax Deducted at Source, withheld by a payer from certain payments and remitted to
government; recorded as a distinct party-ledger deduction.

**Udyam registration.** The free government registration for MSMEs, issuing an Udyam number
that is distinct from a GSTIN.

**unit of measure.** The unit for an item's quantity, such as piece, kilogram, metre, or
hour. Quantities use a fixed decimal scale.

**UPI.** Unified Payments Interface, an instant bank-to-bank payment system. OpenBill
records UPI payments but does not process them.

**UPI QR.** A QR code encoding a UPI payee address and optional amount. OpenBill generates
a static QR; settlement is recorded manually.

**UTGST.** Union Territory GST, the counterpart of SGST, paired with CGST on
intra-union-territory supplies where applicable.

**voucher.** A posted document that affects the ledger or stock, such as an invoice, note,
payment, or adjustment. Drafts are not accounting vouchers.

**wholesale price.** The price offered to trade or bulk buyers, usually below retail; stored
per item and selectable at billing.

**zero-rated supply.** A 0 percent supply, typically exports or SEZ supplies. No output tax
applies, but input tax credit is generally available and refundable.
