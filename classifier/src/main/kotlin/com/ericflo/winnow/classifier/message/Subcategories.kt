package com.ericflo.winnow.classifier.message

/**
 * One fine-grained kind of text, belonging to one of the six [Category]s. A classifier service
 * is asked to pick among these rather than among the six directly: a precise option ("fake toll
 * notice", "landlord maintenance notice") is easier to recognize than a broad one, and the
 * probabilities are then added up into the six (see [Subcategories.aggregate]). The keys are
 * sent as the question's options, so wording changes here change classification behavior.
 *
 * Order matters too: Jev leans toward options listed first, so personal ones come first and
 * spam last, making a close call lean toward letting a text through rather than hiding it.
 */
data class Subcategory(val key: String, val parent: Category, val description: String)

object Subcategories {
    private fun personal(key: String, description: String) = Subcategory(key, Category.PERSONAL, description)
    private fun reminder(key: String, description: String) = Subcategory(key, Category.REMINDER, description)
    private fun transactional(key: String, description: String) = Subcategory(key, Category.TRANSACTIONAL, description)
    private fun marketing(key: String, description: String) = Subcategory(key, Category.MARKETING, description)
    private fun political(key: String, description: String) = Subcategory(key, Category.POLITICAL, description)
    private fun spam(key: String, description: String) = Subcategory(key, Category.SPAM, description)

    val all: List<Subcategory> = listOf(
        personal("family", "A family member writing personally"),
        personal("friend_chat", "A friend chatting, joking or catching up"),
        personal("making_plans", "Someone the user knows arranging to meet, eat, call or travel together"),
        personal("running_late", "Someone the user knows saying they're late, on the way, or outside"),
        personal("partner", "A partner or date writing personally"),
        personal("coworker", "A coworker or colleague writing about work, as a person (not an automated system)"),
        personal("neighbor", "A neighbor writing personally"),
        personal("group_chat", "A message in a group conversation among people who know each other"),
        personal("photo_share", "Someone the user knows sharing a photo, link or video"),
        personal("thanks_or_reply", "A short personal reply: thanks, ok, lol, sounds good, an emoji"),
        personal("question_for_user", "Someone the user knows asking them a personal question"),
        personal("new_number_known", "Someone the user knows writing from a new number and saying who they are with real context"),
        personal("service_person", "A tradesperson, tutor, sitter or other individual the user hired, writing as themselves"),
        personal("congratulations", "Someone the user knows sending congratulations, condolences or birthday wishes"),

        reminder("landlord_maintenance", "A landlord or property manager asking the user to do or allow something: test the heater, filter changes, entry for repairs"),
        reminder("building_notice", "A building or HOA notice: water shutoff, elevator work, pest control, parking changes"),
        reminder("rent_or_dues_due", "Rent, HOA dues or tuition coming due, from the landlord, school or association itself (not a bill alert from a payment app)"),
        reminder("school_notice", "A school or daycare notice: closure, early pickup, picture day, forms to return, lunch money"),
        reminder("team_or_club", "A coach, team, club, church or class organizer with a schedule change, practice or event logistics"),
        reminder("utility_outage", "A utility or city warning of a planned outage, boil notice, street sweeping or trash schedule change"),
        reminder("employer_notice", "An employer or manager with shift, schedule or workplace logistics"),
        reminder("doctor_office_notice", "A doctor's or dentist's office with something to do that the user didn't just book: overdue checkup, forms, office closure"),
        reminder("pickup_ready", "Something the user left or ordered in person is ready, from the person or place holding it (dry cleaning, repair shop, library hold)"),
        reminder("community_notice", "A community, library, neighborhood or volunteer group notice with no sale or donation ask"),
        reminder("seasonal_reminder", "A reminder to do something seasonal or periodic: winterize, renew a permit, change smoke detector batteries"),

        transactional("verification_code", "A login, sign-up or verification code the user requested"),
        transactional("order_confirmed", "Confirmation of an order or purchase the user made"),
        transactional("order_shipped", "An order the user placed has shipped, with real tracking"),
        transactional("delivery_update", "A real delivery update: out for delivery, delivered, delayed"),
        transactional("ride_or_food_arriving", "A ride, food delivery or courier the user booked is arriving"),
        transactional("appointment_confirmation", "Confirmation or reminder of an appointment the user booked"),
        transactional("reservation", "A restaurant, hotel, travel or event reservation the user made"),
        transactional("travel_update", "A flight, train or trip update for a booking the user has"),
        transactional("bank_alert", "A real alert from the user's bank or card: purchase, deposit, balance, low funds"),
        transactional("bill_due", "A real bill or statement notice from a carrier, utility or service the user pays"),
        transactional("payment_receipt", "A receipt for a payment the user made"),
        transactional("account_change", "A real notice that the user's own account changed: password, email, settings they changed"),
        transactional("prescription_ready", "A pharmacy telling the user their prescription is ready or refilled"),
        transactional("service_status", "A status update for a service the user started: repair, return, refund, support ticket"),
        transactional("subscription_notice", "A real renewal, trial-ending or plan change notice for a subscription the user has"),
        transactional("carrier_notice", "The user's own phone carrier about their plan, data use or roaming"),

        marketing("sale_or_discount", "A sale, discount, coupon or percent-off offer from a business"),
        marketing("restaurant_promo", "A restaurant, cafe or bar promotion or special"),
        marketing("retail_promo", "A store or brand promotion, new arrivals or limited-time deal"),
        marketing("loyalty_points", "Rewards, points or member perks meant to bring the user back to buy"),
        marketing("event_promo", "A business promoting an event, show or class to buy tickets for"),
        marketing("app_reengagement", "An app or service trying to get the user to come back or upgrade"),
        marketing("newsletter", "A newsletter or announcement from a business the user signed up with"),
        marketing("survey_or_review", "A business asking for a review, rating or survey"),
        marketing("membership_upsell", "An offer to join, renew or upgrade a paid membership or plan"),
        marketing("local_business", "A local business (gym, salon, car wash) advertising to customers"),
        marketing("contest_from_brand", "A real brand's giveaway or contest the user signed up for"),
        marketing("auto_dealer_or_service", "A dealership or service shop advertising offers or specials"),

        political("donation_ask", "A campaign, party or PAC asking for a donation or a match"),
        political("political_poll", "A political poll or survey, real or fake"),
        political("petition", "A petition, pledge or 'sign by' deadline from a political group"),
        political("voting_reminder", "A reminder to register, vote or check a polling place, from a campaign or group"),
        political("candidate_news", "Sensational 'BREAKING' or 'devastating news' about politicians, from a campaign"),
        political("advocacy", "An advocacy group or cause asking the user to act, call or give"),
        political("volunteer_ask", "A campaign asking the user to volunteer, canvass or attend"),
        political("misaddressed_fundraising", "Political fundraising addressed to someone else by name"),

        spam("toll_phishing", "A fake unpaid toll or traffic fine with a link to pay"),
        spam("package_phishing", "A fake package hold, address problem or redelivery fee"),
        spam("bank_phishing", "A fake bank or card alert: account locked, suspicious charge, verify now"),
        spam("account_phishing", "A fake alert about an app or online account: Apple, Amazon, Netflix, PayPal, Microsoft"),
        spam("government_phishing", "A fake government, tax, benefits or Social Security notice"),
        spam("refund_or_prize_phishing", "A fake refund, rebate, reward or prize to claim with a link"),
        spam("wrong_number_opener", "A stranger's 'wrong number', 'hi, is this…' or 'are you free to talk?' opener"),
        spam("romance_scam", "A stranger flirting or striking up a friendship out of nowhere"),
        spam("job_scam", "An unsolicited job, remote work or easy-money offer"),
        spam("crypto_investment", "Crypto, trading or investment pitches from strangers"),
        spam("gift_card_or_money_ask", "A stranger asking for gift cards, money or payment-app transfers"),
        spam("loan_or_debt", "Unsolicited loans, debt relief or credit offers"),
        spam("real_estate_lead", "Unsolicited offers to buy the user's house or property"),
        spam("insurance_lead", "Unsolicited insurance, warranty or health plan quotes"),
        spam("lead_generation", "Other unsolicited sales leads and cold pitches"),
        spam("misdirected_bulk", "Mass texts or messages clearly meant for someone else"),
        spam("adult_or_dating", "Adult content or dating-site spam"),
        spam("other_junk", "Other unwanted junk from strangers"),
    )

    private val byKey = all.associateBy { it.key }

    fun of(key: String): Subcategory? = byKey[key]

    /**
     * A provider's probabilities over subcategories (or, from one that answers with the six
     * directly, over categories) added up into the six. Keys it doesn't know are left out.
     */
    fun aggregate(probabilities: Map<String, Double>): Map<Category, Double> {
        val out = HashMap<Category, Double>()
        for ((key, p) in probabilities) {
            val category = byKey[key]?.parent ?: Category.fromKey(key) ?: continue
            out[category] = (out[category] ?: 0.0) + p
        }
        return out
    }

    /** The question's options: each subcategory's description, with the category it counts as. */
    fun options(): Map<String, String> =
        all.associate { it.key to "${it.description}. Counts as ${it.parent.label}." }
}
