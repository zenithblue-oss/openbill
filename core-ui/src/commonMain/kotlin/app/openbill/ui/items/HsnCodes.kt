package app.openbill.ui.items

/**
 * Offline lookup of common HSN headings (goods, 4 digits) and SAC codes (services). Codes come
 * from the Customs Tariff of India and the GST scheme of classification of services published by
 * CBIC (Government of India); descriptions are short paraphrases. No GST rates: they change by
 * notification, so the user picks the rate. See NOTICE.
 */
object HsnCodes {
    val all: List<Pair<String, String>> by lazy {
        DATA.trim().lines().map { it.substringBefore('|') to it.substringAfter('|') }
    }

    /** Matches a code prefix, or every word of [query] in the description; at most [limit] rows. */
    fun search(query: String, limit: Int = 50): List<Pair<String, String>> {
        val q = query.trim()
        if (q.isEmpty()) return all.take(limit)
        if (q.all(Char::isDigit)) return all.filter { it.first.startsWith(q) || q.startsWith(it.first) }.take(limit)
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        return all.filter { (_, d) -> words.all { d.lowercase().contains(it) } }.take(limit)
    }

    private const val DATA = """
0401|Milk and cream, not concentrated
0402|Milk and cream, concentrated or sweetened (milk powder)
0403|Curd, yoghurt, buttermilk
0405|Butter, ghee and other milk fats
0406|Cheese and paneer
0407|Birds' eggs in shell
0701|Potatoes, fresh or chilled
0702|Tomatoes, fresh or chilled
0703|Onions, shallots and garlic
0713|Dried pulses (dal)
0801|Coconuts, cashew nuts
0901|Coffee
0902|Tea
0904|Pepper, dried or ground chillies
0910|Ginger, turmeric and other spices
1001|Wheat
1006|Rice
1101|Wheat flour (atta, maida)
1507|Soya-bean oil
1508|Groundnut oil
1512|Sunflower, safflower or cotton-seed oil
1701|Cane or beet sugar
1704|Sugar confectionery without cocoa
1806|Chocolate and food containing cocoa
1902|Pasta and noodles
1904|Cereal flakes, puffed rice
1905|Bread, biscuits, cakes and pastry
2009|Fruit and vegetable juices
2101|Coffee and tea extracts, instant coffee
2105|Ice cream
2106|Food preparations n.e.s. (namkeen, mixes)
2201|Mineral and aerated water, unsweetened
2202|Soft drinks and flavoured water
2501|Salt
2523|Cement
2710|Petroleum oils, lubricants
2711|Petroleum gases (LPG)
3004|Medicines in measured doses
3005|Bandages and dressings
3303|Perfumes
3304|Beauty and make-up preparations
3305|Hair preparations (shampoo, hair oil)
3306|Toothpaste and oral hygiene preparations
3307|Shaving preparations and deodorants
3401|Soap
3402|Detergents and washing preparations
3808|Insecticides and disinfectants
3923|Plastic boxes, bags and containers
3924|Plastic tableware and household articles
3926|Other articles of plastic
4011|New rubber tyres
4016|Other articles of rubber
4202|Bags, wallets and suitcases
4802|Uncoated paper
4818|Toilet paper, tissues and napkins
4819|Paper cartons and boxes
4820|Registers, notebooks and diaries
4901|Printed books
4911|Other printed matter
5208|Woven cotton fabrics
6109|T-shirts and vests, knitted
6203|Men's suits and trousers
6204|Women's suits, dresses and skirts
6205|Men's shirts
6302|Bed linen, table linen and towels
6402|Footwear with rubber or plastic uppers
6403|Footwear with leather uppers
6810|Cement and concrete articles (blocks, tiles)
6907|Ceramic tiles
6911|Porcelain tableware and kitchenware
7013|Glassware for table and kitchen
7113|Jewellery of precious metal
7214|Iron or steel bars and rods
7308|Iron or steel structures
7318|Screws, bolts and nuts
7323|Steel utensils and kitchen articles
7615|Aluminium utensils and kitchen articles
8301|Locks and padlocks
8414|Fans, air pumps and compressors
8415|Air conditioners
8418|Refrigerators and freezers
8443|Printers and copiers
8450|Washing machines
8471|Computers and computer units
8481|Taps and valves
8504|Transformers, power supplies and inverters
8507|Batteries (electric accumulators)
8509|Domestic appliances (mixer grinders)
8513|Torches and portable lamps
8516|Water heaters, irons, microwave ovens
8517|Mobile phones and network equipment
8518|Speakers, microphones and headphones
8523|Memory cards and pen drives
8528|Monitors, projectors and televisions
8536|Switches, plugs, sockets and fuses
8539|Electric lamps (LED lamps)
8544|Insulated wires and cables
8703|Motor cars
8708|Parts of motor vehicles
8711|Motorcycles and scooters
8712|Bicycles
9004|Spectacles and goggles
9018|Medical and surgical instruments
9102|Wrist watches
9401|Seats and chairs
9403|Other furniture
9404|Mattresses and bedding
9405|Lamps and light fittings
9503|Toys
9608|Pens and markers
9954|Construction services
9961|Wholesale trade services
9962|Retail trade services
9963|Accommodation, food and beverage services
996331|Restaurant, cafe and takeaway services
9964|Passenger transport services
9965|Goods transport services
996511|Road transport of goods
9967|Transport support services (warehousing, cargo handling)
9968|Postal and courier services
9971|Financial and related services
9972|Real estate services
997212|Rental of non-residential property
9973|Leasing or rental without operator
9981|Research and development services
9982|Legal and accounting services
998222|Accounting and bookkeeping services
9983|Professional, technical and business services
998311|Management consulting services
998313|IT consulting and support services
998314|IT design and development services
998315|Hosting and IT infrastructure services
998361|Advertising services
9984|Telecommunication and information services
9985|Support services (staffing, security, cleaning, travel)
9986|Support services to agriculture and mining
9987|Maintenance, repair and installation services
9988|Job work (manufacturing on others' inputs)
9989|Printing and other manufacturing services
9992|Education services
999293|Commercial training and coaching
9993|Health and social care services
9994|Sewage and waste services
9995|Membership organisation services
9996|Recreational, cultural and sporting services
9997|Other services (laundry, beauty, personal care)
9998|Domestic services
"""
}
