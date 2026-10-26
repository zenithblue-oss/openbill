package app.openbill.core.domain.report

/** Reviewed golden outputs for [ReportFixture] (October 2026). Regenerate only after checking the diff. */
internal val GSTR1_GOLDEN = """{
  "gstin": "27ABCDE1234F1Z0",
  "fp": "102026",
  "b2b": [
    {
      "ctin": "27AAAPL1234C1ZE",
      "inv": [
        {
          "inum": "INV-0001",
          "idt": "02-10-2026",
          "val": 1180.00,
          "pos": "27",
          "rchrg": "N",
          "inv_typ": "R",
          "itms": [
            {
              "num": 1,
              "itm_det": {
                "txval": 1000.00,
                "rt": 18,
                "camt": 90.00,
                "samt": 90.00,
                "csamt": 0.00
              }
            }
          ]
        }
      ]
    }
  ],
  "b2cl": [
    {
      "pos": "29",
      "inv": [
        {
          "inum": "INV-0002",
          "idt": "05-10-2026",
          "val": 141600.00,
          "itms": [
            {
              "num": 1,
              "itm_det": {
                "txval": 120000.00,
                "rt": 18,
                "iamt": 21600.00,
                "csamt": 0.00
              }
            }
          ]
        }
      ]
    }
  ],
  "b2cs": [
    {
      "sply_ty": "INTRA",
      "pos": "27",
      "typ": "OE",
      "txval": 500.00,
      "rt": 12,
      "camt": 30.00,
      "samt": 30.00,
      "csamt": 0.00
    }
  ],
  "cdnr": [
    {
      "ctin": "27AAAPL1234C1ZE",
      "nt": [
        {
          "ntty": "C",
          "nt_num": "CN-0001",
          "nt_dt": "10-10-2026",
          "val": 118.00,
          "pos": "27",
          "rchrg": "N",
          "inv_typ": "R",
          "itms": [
            {
              "num": 1,
              "itm_det": {
                "txval": 100.00,
                "rt": 18,
                "camt": 9.00,
                "samt": 9.00,
                "csamt": 0.00
              }
            }
          ]
        }
      ]
    }
  ],
  "hsn": {
    "hsn_b2b": [
      {
        "num": 1,
        "hsn_sc": "7318",
        "desc": "Steel Bolt",
        "uqc": "NOS",
        "qty": 9,
        "rt": 18,
        "txval": 900.00,
        "iamt": 0.00,
        "camt": 81.00,
        "samt": 81.00,
        "csamt": 0.00
      }
    ],
    "hsn_b2c": [
      {
        "num": 1,
        "hsn_sc": "8413",
        "desc": "Water Pump",
        "uqc": "NOS",
        "qty": 2,
        "rt": 18,
        "txval": 120000.00,
        "iamt": 21600.00,
        "camt": 0.00,
        "samt": 0.00,
        "csamt": 0.00
      },
      {
        "num": 2,
        "hsn_sc": "9987",
        "desc": "Fitting Service",
        "uqc": "NA",
        "qty": 0,
        "rt": 12,
        "txval": 500.00,
        "iamt": 0.00,
        "camt": 30.00,
        "samt": 30.00,
        "csamt": 0.00
      }
    ]
  },
  "doc_issue": {
    "doc_det": [
      {
        "doc_num": 1,
        "docs": [
          {
            "num": 1,
            "from": "INV-0001",
            "to": "INV-0004",
            "totnum": 4,
            "cancel": 1,
            "net_issue": 3
          }
        ]
      },
      {
        "doc_num": 5,
        "docs": [
          {
            "num": 1,
            "from": "CN-0001",
            "to": "CN-0001",
            "totnum": 1,
            "cancel": 0,
            "net_issue": 1
          }
        ]
      }
    ]
  }
}
"""

internal val TALLY_GOLDEN = """<ENVELOPE>
 <HEADER>
  <TALLYREQUEST>Import Data</TALLYREQUEST>
 </HEADER>
 <BODY>
  <IMPORTDATA>
   <REQUESTDESC>
    <REPORTNAME>Vouchers</REPORTNAME>
    <STATICVARIABLES>
     <SVCURRENTCOMPANY>Sharma Traders</SVCURRENTCOMPANY>
    </STATICVARIABLES>
   </REQUESTDESC>
   <REQUESTDATA>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Bharat Supplies" ACTION="Create">
      <NAME.LIST>
       <NAME>Bharat Supplies</NAME>
      </NAME.LIST>
      <PARENT>Sundry Creditors</PARENT>
      <ISBILLWISEON>Yes</ISBILLWISEON>
      <PARTYGSTIN>29AAAPL1234C1ZA</PARTYGSTIN>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Purchase" ACTION="Create">
      <NAME.LIST>
       <NAME>Purchase</NAME>
      </NAME.LIST>
      <PARENT>Purchase Accounts</PARENT>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Input IGST" ACTION="Create">
      <NAME.LIST>
       <NAME>Input IGST</NAME>
      </NAME.LIST>
      <PARENT>Duties &amp; Taxes</PARENT>
      <TAXTYPE>GST</TAXTYPE>
      <GSTDUTYHEAD>Integrated Tax</GSTDUTYHEAD>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Acme Stores" ACTION="Create">
      <NAME.LIST>
       <NAME>Acme Stores</NAME>
      </NAME.LIST>
      <PARENT>Sundry Debtors</PARENT>
      <ISBILLWISEON>Yes</ISBILLWISEON>
      <PARTYGSTIN>27AAAPL1234C1ZE</PARTYGSTIN>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Sales" ACTION="Create">
      <NAME.LIST>
       <NAME>Sales</NAME>
      </NAME.LIST>
      <PARENT>Sales Accounts</PARENT>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Output CGST" ACTION="Create">
      <NAME.LIST>
       <NAME>Output CGST</NAME>
      </NAME.LIST>
      <PARENT>Duties &amp; Taxes</PARENT>
      <TAXTYPE>GST</TAXTYPE>
      <GSTDUTYHEAD>Central Tax</GSTDUTYHEAD>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Output SGST" ACTION="Create">
      <NAME.LIST>
       <NAME>Output SGST</NAME>
      </NAME.LIST>
      <PARENT>Duties &amp; Taxes</PARENT>
      <TAXTYPE>GST</TAXTYPE>
      <GSTDUTYHEAD>State Tax</GSTDUTYHEAD>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Ravi Kumar" ACTION="Create">
      <NAME.LIST>
       <NAME>Ravi Kumar</NAME>
      </NAME.LIST>
      <PARENT>Sundry Debtors</PARENT>
      <ISBILLWISEON>Yes</ISBILLWISEON>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Output IGST" ACTION="Create">
      <NAME.LIST>
       <NAME>Output IGST</NAME>
      </NAME.LIST>
      <PARENT>Duties &amp; Taxes</PARENT>
      <TAXTYPE>GST</TAXTYPE>
      <GSTDUTYHEAD>Integrated Tax</GSTDUTYHEAD>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <LEDGER NAME="Walk-in" ACTION="Create">
      <NAME.LIST>
       <NAME>Walk-in</NAME>
      </NAME.LIST>
      <PARENT>Sundry Debtors</PARENT>
      <ISBILLWISEON>Yes</ISBILLWISEON>
     </LEDGER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <VOUCHER VCHTYPE="Purchase" ACTION="Create">
      <DATE>20261001</DATE>
      <VOUCHERTYPENAME>Purchase</VOUCHERTYPENAME>
      <VOUCHERNUMBER>PUR-0001</VOUCHERNUMBER>
      <REFERENCE>BS/77</REFERENCE>
      <PARTYLEDGERNAME>Bharat Supplies</PARTYLEDGERNAME>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Bharat Supplies</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>1180.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Purchase</LEDGERNAME>
       <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
       <AMOUNT>-1000.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Input IGST</LEDGERNAME>
       <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
       <AMOUNT>-180.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
     </VOUCHER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <VOUCHER VCHTYPE="Sales" ACTION="Create">
      <DATE>20261002</DATE>
      <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>
      <VOUCHERNUMBER>INV-0001</VOUCHERNUMBER>
      <PARTYLEDGERNAME>Acme Stores</PARTYLEDGERNAME>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Acme Stores</LEDGERNAME>
       <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
       <AMOUNT>-1180.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Sales</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>1000.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Output CGST</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>90.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Output SGST</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>90.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
     </VOUCHER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <VOUCHER VCHTYPE="Sales" ACTION="Create">
      <DATE>20261005</DATE>
      <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>
      <VOUCHERNUMBER>INV-0002</VOUCHERNUMBER>
      <PARTYLEDGERNAME>Ravi Kumar</PARTYLEDGERNAME>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Ravi Kumar</LEDGERNAME>
       <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
       <AMOUNT>-141600.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Sales</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>120000.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Output IGST</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>21600.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
     </VOUCHER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <VOUCHER VCHTYPE="Sales" ACTION="Create">
      <DATE>20261007</DATE>
      <VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>
      <VOUCHERNUMBER>INV-0003</VOUCHERNUMBER>
      <PARTYLEDGERNAME>Walk-in</PARTYLEDGERNAME>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Walk-in</LEDGERNAME>
       <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
       <AMOUNT>-560.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Sales</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>500.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Output CGST</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>30.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Output SGST</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>30.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
     </VOUCHER>
    </TALLYMESSAGE>
    <TALLYMESSAGE xmlns:UDF="TallyUDF">
     <VOUCHER VCHTYPE="Credit Note" ACTION="Create">
      <DATE>20261010</DATE>
      <VOUCHERTYPENAME>Credit Note</VOUCHERTYPENAME>
      <VOUCHERNUMBER>CN-0001</VOUCHERNUMBER>
      <PARTYLEDGERNAME>Acme Stores</PARTYLEDGERNAME>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Acme Stores</LEDGERNAME>
       <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
       <AMOUNT>118.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Sales</LEDGERNAME>
       <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
       <AMOUNT>-100.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Output CGST</LEDGERNAME>
       <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
       <AMOUNT>-9.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
      <ALLLEDGERENTRIES.LIST>
       <LEDGERNAME>Output SGST</LEDGERNAME>
       <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>
       <AMOUNT>-9.00</AMOUNT>
      </ALLLEDGERENTRIES.LIST>
     </VOUCHER>
    </TALLYMESSAGE>
   </REQUESTDATA>
  </IMPORTDATA>
 </BODY>
</ENVELOPE>
"""
