# OpenBill help

These pages are bundled into the app (More > Help) and work offline. Each "##" heading is one
topic; keep the wording plain and the steps short.

## Getting started

1. Fill in the business profile (More > Edit business details): name, address, city, phone, GSTIN
   and state. These print on every invoice.
2. Add your items (Items tab) with price, unit, HSN/SAC code and GST rate.
3. Add the customers and suppliers you bill (Parties tab).
4. Create an invoice (Invoices tab > New invoice), check it and post it.
5. Make a backup (More > Backup and restore) and keep the file somewhere safe.

The "Get started" card on the dashboard ticks these off as you go; hide it when you are done.

## Invoices and other documents

- A new invoice starts as a draft. Drafts can be edited freely; posting gives it a number and
  enters it in the books and stock.
- A posted invoice is never edited silently. Cancel it with a reason, or make a sales return
  (credit note) for goods that come back.
- Quotations, proformas, delivery challans and sales orders are under Money > Documents; convert
  one to an invoice when the customer confirms.
- Templates, copy labels, logo, signature, bank details and UPI QR are set in More > Invoice
  templates.

## Payments, reminders and payment links

- Record money received with Money > Payment in; choose the invoices it settles.
- A party's ledger (Parties > party > Ledger) shows what is due by age and can send a payment
  reminder through WhatsApp, SMS or any app.
- Add your UPI ID in More > Invoice templates to print a UPI QR on invoices and add a UPI
  payment link to shared invoices, emails, SMS and reminders. OpenBill never takes payments
  itself; the customer pays from their own UPI app.

## Sharing by SMS, email and WhatsApp

- On an invoice, "Share PDF" opens the share sheet (WhatsApp, Drive, Bluetooth and so on).
- "SMS" opens your SMS app with the amount, due date and payment link filled in.
- "Email with PDF" opens your mail app with the PDF attached. On a computer the PDF is saved
  first and the mail draft opens; attach the saved file.
- Nothing is sent until you press Send in that app.

## Stock

- Items with "Track stock" on move in and out with purchases, sales, returns, adjustments and
  transfers between godowns.
- Set a low-stock level and a reorder quantity on an item to see it in the low-stock report and
  the notifications.
- Batch and expiry tracking shows near-expiry batches on the dashboard; the window is set in More.

## Reports and GST

- Reports (Money > Reports and GST) cover sales and purchase registers, day book, profit and loss,
  outstanding with ageing, stock, GSTR-1, GSTR-3B working, HSN summary and more.
- GST figures are working reports to file from; check them before filing on the GST portal.
- The CA pack (under Reports and GST) bundles a period's reports into one ZIP for your accountant.

## Backup and restore

- More > Backup and restore saves everything (all businesses, invoices, logos and attachments)
  into one file, optionally encrypted with a password you must not forget.
- Restore replaces the data on this device with the backup; a safety copy of the current data is
  kept first.
- The notifications remind you when no backup was made for a week (change it in More >
  Notifications).

## Users and PINs

- The owner can add staff in More > Users and roles: manager, cashier and accountant (CA), each
  with their own PIN.
- Roles limit what each person can do; a cashier bills, an accountant only reads and exports.
- Forgot a staff PIN? The owner resets it. Keep the owner PIN safe.

## Import from other software

- More > Import and export > Import parties from CSV or Import items from CSV read a
  spreadsheet saved as CSV.
- Exports from other billing software and Excel are matched by column name: Party Name, Mobile
  No., GSTIN/UIN, Opening Balance with Dr/Cr, Item Name, HSN Code, GST %, Selling Price, Opening
  Stock and similar.
- "Save CSV templates" gives empty sheets with every column. Every row is checked first; nothing
  is imported until all rows are valid.

## Notifications

- The bell on the dashboard lists low stock, expiring batches, customers past their credit
  days, backup reminders and failures, and invoices made by recurring schedules.
- Tap a notification to open the item, party or screen it is about. Switch categories off in
  More > Notifications.

## Privacy and your data

- Your business data stays on this device. OpenBill has no account, no cloud and no ads.
- Diagnostics (More > Help > Diagnostics) contain only the app version, device, database version,
  settings switches and recent error messages; you see the full text before sharing it.

## Troubleshooting

- A sale cannot be posted for lack of stock: allow negative stock in More, or record the
  purchase or opening stock first.
- A sale is blocked by the credit limit: record the customer's payment, raise the limit, or
  switch the block to a warning in More.
- The PDF looks wrong: try another template in More > Invoice templates.
- Something else: use More > Help > Contact support, and attach the diagnostics if asked.
