package com.mink.narrative

import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.signals.AppTaxonomy
import com.mink.signals.AppTaxonomy.AppCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One derived "story" card: an inference about the person or device, not a raw
 * reading. It shares the title/body/basis shape of [NarrativeCard] so the
 * summary screen can render both with the same card view.
 */
data class StoryCard(
    val id: String,
    val title: String,
    val body: String,
    /** Phrased "Read from ..." / "Inferred from ..." to match the Loupe voice. */
    val basis: String,
)

/**
 * Non-snapshot inputs the story derivation needs, gathered by the screen. The
 * clock and every app-access reading are injected here so the derivation stays
 * pure: the same context and snapshot always yield the same cards.
 */
data class DeviceStoryContext(
    val nowMs: Long,
    /** Min firstInstallMs across non-system apps, or null when there are none. */
    val earliestUserInstallMs: Long?,
    /** Non-system package names, used for the app-inference card. */
    val userPackageNames: List<String>,
)

/**
 * Pure, deterministic derivation of human "story" cards from the signal
 * snapshot Mink already collected. This is the Android analogue of Loupe's
 * FingerprintNarrative owner/travel cards and AppInferenceEngine: it never
 * reads the OS itself and never leaves the device, it only re-reads and
 * combines signals the providers already gathered.
 *
 * No Android imports live here on purpose. Every non-snapshot input arrives via
 * [DeviceStoryContext], so the whole file is unit-testable on the plain JVM.
 * Every card fires only when its real inputs are present, and the owner card
 * never invents a name the regex did not capture.
 */
object StoryNarrative {

    /** One year in milliseconds, used for the coarse "about N years ago" phrase. */
    private const val YEAR_MS = 31_536_000_000L

    /**
     * The earliest install timestamp we treat as real (2010-01-01 UTC). A non-system
     * app can report a firstInstallMs of 0 or a build date; anything older than this is
     * implausible and must not be used to date the phone.
     */
    const val MIN_PLAUSIBLE_INSTALL_MS = 1_262_304_000_000L

    /**
     * Assemble the story cards in a fixed order. Each helper returns a card only
     * when its inputs are present and interesting; the nulls are dropped here.
     */
    fun build(
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
        context: DeviceStoryContext,
    ): List<StoryCard> = listOfNotNull(
        travelCard(snapshot),
        ownerCard(snapshot),
        regionSimCard(snapshot),
        uptimeCard(snapshot),
        birthdayCard(context),
        appsCard(context),
    )

    // ---- Cards ----

    /** Time zone points at one country while the region setting names another. */
    private fun travelCard(snapshot: Map<SignalCategory, List<FingerprintSignal>>): StoryCard? {
        val tzId = signalOf(snapshot, SignalCategory.LOCALE, "timeZone")?.value?.trim()
            ?.ifBlank { null } ?: return null
        val tzCountry = timeZoneCountry(tzId) ?: return null
        val region = localeCountry(snapshot) ?: return null
        if (sovereign(tzCountry).equals(sovereign(region), ignoreCase = true)) return null

        val roaming = signalOf(snapshot, SignalCategory.TELEPHONY, "roaming")?.value?.trim() == "true"
        val roamingNote = if (roaming) " Your SIM also reports roaming." else ""
        return StoryCard(
            id = "travel",
            title = "You may be travelling",
            body = "Your time zone points to ${countryName(tzCountry)}, but your phone's region " +
                "is set to ${countryName(region)}.$roamingNote",
            basis = "Inferred from a time zone ($tzId) that does not match your region setting.",
        )
    }

    /** A paired device's name may leak its owner's name. */
    private fun ownerCard(snapshot: Map<SignalCategory, List<FingerprintSignal>>): StoryCard? {
        val candidates = buildList {
            signalOf(snapshot, SignalCategory.BLUETOOTH, "bonded")?.entries.orEmpty()
                .forEach { entry -> entry.label.trim().takeIf { it.isNotBlank() }?.let { add(it) } }
            signalOf(snapshot, SignalCategory.BLUETOOTH, "name")?.value?.trim()
                ?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (candidates.isEmpty()) return null

        for (candidate in candidates) {
            val owner = ownerName(candidate) ?: continue
            return StoryCard(
                id = "owner",
                title = "Your gear may carry your name",
                body = "A device paired to this phone is named \"$candidate\", so your name might " +
                    "be $owner.",
                basis = "Read from the name of a device paired to this phone.",
            )
        }
        return StoryCard(
            id = "owner",
            title = "Your gear is named after you or your things",
            body = "One of your paired devices is named \"${candidates.first()}\". Device names " +
                "often include their owner's name.",
            basis = "Read from the name of a device paired to this phone.",
        )
    }

    /** The region setting and the SIM's country disagree. */
    private fun regionSimCard(snapshot: Map<SignalCategory, List<FingerprintSignal>>): StoryCard? {
        val region = localeCountry(snapshot) ?: return null
        val sim = entryValue(signalOf(snapshot, SignalCategory.TELEPHONY, "country"), "SIM")
            ?.trim()?.uppercase()?.ifBlank { null } ?: return null
        if (sovereign(region).equals(sovereign(sim), ignoreCase = true)) return null
        return StoryCard(
            id = "regionSim",
            title = "Your region and SIM do not match",
            body = "Your phone's region is set to ${countryName(region)}, but your SIM is from " +
                "${countryName(sim)}. That mismatch is itself distinctive, and it can mean travel, " +
                "a foreign SIM, or a privacy setup.",
            basis = "Comparing your device region with your SIM's country.",
        )
    }

    /** How long the phone has been up links any two apps that read it. */
    private fun uptimeCard(snapshot: Map<SignalCategory, List<FingerprintSignal>>): StoryCard? {
        val uptime = signalOf(snapshot, SignalCategory.SYSTEM_INFO, "uptime")?.value?.trim()
            ?.ifBlank { null } ?: return null
        val bootTime = signalOf(snapshot, SignalCategory.SYSTEM_INFO, "bootTime")?.value?.trim()
            ?.ifBlank { null }
        val since = if (bootTime != null) ", since $bootTime" else ""
        return StoryCard(
            id = "uptime",
            title = "This phone has been running a while",
            body = "It has been up for $uptime$since. Two apps that read this agree to the second, " +
                "which links them to the same phone.",
            basis = "Read from the system boot time, which any app can see.",
        )
    }

    /** The oldest installed app dates the phone's setup, which is close to unique. */
    private fun birthdayCard(context: DeviceStoryContext): StoryCard? {
        val earliest = context.earliestUserInstallMs ?: return null
        if (earliest < MIN_PLAUSIBLE_INSTALL_MS || earliest > context.nowMs) return null
        val body = buildString {
            append("The oldest app on it was installed ${monthYear(earliest)}")
            val years = ((context.nowMs - earliest + YEAR_MS / 2) / YEAR_MS).toInt()
            if (years >= 1) {
                append(" — about $years ")
                append(if (years == 1) "year" else "years")
                append(" ago")
            }
            append(". The date an account first set up a phone is close to unique.")
        }
        return StoryCard(
            id = "birthday",
            title = "This phone has been yours for a while",
            body = body,
            basis = "Inferred from the oldest app installed on this phone.",
        )
    }

    /** The lifestyle mix your installed apps hints at, in one combined card. */
    private fun appsCard(context: DeviceStoryContext): StoryCard? {
        val profile = AppTaxonomy.profile(context.userPackageNames)
        val phrases = AppCategory.entries
            .mapNotNull { category -> appPhrase(category, profile[category] ?: 0) }
            .take(4)
        if (phrases.isEmpty()) return null
        return StoryCard(
            id = "apps",
            title = "What your apps hint at",
            body = "The mix of apps you have installed suggests ${oxfordJoin(phrases)}. Any app " +
                "that can list your packages reads this without a prompt.",
            basis = "Inferred from the apps installed on this phone.",
        )
    }

    /** The phrase a category earns once its count clears the threshold, else null. */
    private fun appPhrase(category: AppCategory, count: Int): String? = when (category) {
        AppCategory.FINANCE -> if (count >= 2) "you may manage money on your phone" else null
        AppCategory.CRYPTO -> if (count >= 1) "you may hold crypto" else null
        AppCategory.SOCIAL -> if (count >= 3) "you may be a heavy social-media user" else null
        AppCategory.MESSAGING -> if (count >= 2) "you may use several messengers" else null
        AppCategory.DATING -> if (count >= 1) "you may be dating" else null
        AppCategory.HEALTH -> if (count >= 1) "you may track your health" else null
        AppCategory.VPN -> if (count >= 1) "you use a VPN" else null
        AppCategory.SHOPPING -> if (count >= 2) "you may shop online" else null
        AppCategory.TRAVEL -> if (count >= 2) "you may travel" else null
    }

    // ---- Pure helpers ----

    /**
     * A compact IANA time-zone-id -> ISO 3166-1 alpha-2 country map, ported from
     * Loupe's TimeZoneCountries. Unknown zones return null.
     */
    fun timeZoneCountry(tzId: String): String? = TZ_COUNTRIES[tzId] ?: TZ_ALIASES[tzId]

    /**
     * Best-effort extraction of an owner's first name from a device name, ported
     * from Loupe's FingerprintNarrative owner-name patterns. The two high-precision
     * rules are tried in order and the first clean capture of length 1..24 wins. A
     * capture is rejected when it is an all-caps brand acronym (length >= 2, e.g.
     * JBL) or a known non-name room/relation word, so a confident wrong name is
     * never shown; a name is never invented when nothing matches. Both rules are
     * case-sensitive, matching Loupe.
     */
    fun ownerName(deviceName: String): String? {
        val name = deviceName.trim()
        if (name.isEmpty()) return null
        for (pattern in OWNER_PATTERNS) {
            val match = pattern.find(name) ?: continue
            val captured = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (captured.length !in 1..24) continue
            if (captured.length >= 2 && captured == captured.uppercase()) continue
            if (captured.lowercase() in NON_NAME_WORDS) continue
            return captured
        }
        return null
    }

    /**
     * Reads the locale region (the Country entry, uppercased), or null. A non-ISO
     * alpha-2 value (a UN M.49 numeric region such as "419") is rejected, which
     * suppresses both the travel and region/SIM cards for global locales.
     */
    private fun localeCountry(snapshot: Map<SignalCategory, List<FingerprintSignal>>): String? =
        entryValue(signalOf(snapshot, SignalCategory.LOCALE, "components"), "Country")
            ?.trim()?.uppercase()?.ifBlank { null }
            ?.takeIf { ISO_ALPHA2.matches(it) }

    /** The display name for an ISO country code, falling back to the code itself. */
    private fun countryName(iso: String): String =
        Locale("", iso).getDisplayCountry(Locale.US).ifBlank { iso }

    /**
     * Collapses a dependent territory code onto its sovereign region (PR -> US),
     * so a territory resident whose region names the parent country does not read
     * as a permanent traveller. Unknown or already-sovereign codes come back
     * uppercased unchanged.
     */
    private fun sovereign(iso: String): String =
        TERRITORY_TO_SOVEREIGN[iso.uppercase()] ?: iso.uppercase()

    /** A coarse "Month yyyy" label for an epoch, pinned to UTC for determinism. */
    private fun monthYear(epochMs: Long): String {
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    /** Joins phrases with an Oxford comma: "a"; "a and b"; "a, b, and c". */
    private fun oxfordJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and ${items.last()}"
    }

    /** Looks up a single signal in the snapshot by its stable "<category>.<key>" id. */
    private fun signalOf(
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
        category: SignalCategory,
        key: String,
    ): FingerprintSignal? =
        snapshot[category]?.firstOrNull { it.id == "${category.id}.$key" }

    /** Reads a rich signal's entry value by label, case-insensitively. */
    private fun entryValue(signal: FingerprintSignal?, label: String): String? =
        signal?.entries?.firstOrNull { it.label.equals(label, ignoreCase = true) }?.value

    /**
     * Owner-name patterns, tried in order. Group 1 is the captured name. Only the
     * two high-precision rules survive; the low-precision bare-genitive and CJK
     * particle rules were dropped because a truncated or wrong name (e.g. "Chris
     * Beats" -> "Chri", or a room word) is worse than showing no name.
     *   1. English possessive: "Talal's AirPods" -> "Talal".
     *   2. Trailing Romance/German connector: "AirPods de Talal" -> "Talal".
     */
    private val OWNER_PATTERNS: List<Regex> = listOf(
        Regex("""^([\p{Lu}][\p{L}'’‘\-]*)['’‘]s\s"""),
        Regex("""(?:\sde\s|\sdi\s|\sd['’]|\svon\s)([\p{Lu}][\p{L}\-]+)\s*$"""),
    )

    /**
     * Captures that are never a person's name: brand rooms, relation words, and
     * common device-name nouns. Compared against the capture's lowercase form.
     */
    private val NON_NAME_WORDS: Set<String> = setOf(
        "room", "sala", "salon", "salón", "office", "home", "living", "livingroom", "tv",
        "speaker", "mom", "dad", "mum", "kitchen", "bedroom", "car", "bureau", "buro", "büro",
    )

    /** An ISO 3166-1 alpha-2 region code: exactly two ASCII uppercase letters. */
    private val ISO_ALPHA2: Regex = Regex("^[A-Z]{2}$")

    /**
     * Dependent territory ISO code -> its sovereign region code. A territory
     * resident often sets region to the parent country, so the tz-country (e.g.
     * PR) and region (US) disagree forever; normalizing both sides through this
     * map before comparing kills that permanent false "you may be travelling".
     */
    private val TERRITORY_TO_SOVEREIGN: Map<String, String> = mapOf(
        // United States and its territories
        "PR" to "US", "GU" to "US", "VI" to "US", "AS" to "US", "MP" to "US", "UM" to "US",
        // France and its territories
        "GP" to "FR", "MQ" to "FR", "RE" to "FR", "YT" to "FR", "PM" to "FR", "BL" to "FR",
        "MF" to "FR", "NC" to "FR", "PF" to "FR", "WF" to "FR", "TF" to "FR",
        // Netherlands
        "AW" to "NL", "CW" to "NL", "SX" to "NL", "BQ" to "NL",
        // United Kingdom crown dependencies / territories
        "JE" to "GB", "GG" to "GB", "IM" to "GB", "GI" to "GB", "FK" to "GB", "BM" to "GB",
        "KY" to "GB", "VG" to "GB", "TC" to "GB", "MS" to "GB", "AI" to "GB", "SH" to "GB",
        // Denmark, Norway, Australia, China, Finland
        "FO" to "DK", "GL" to "DK", "SJ" to "NO", "AX" to "FI", "HK" to "CN", "MO" to "CN",
        "CX" to "AU", "CC" to "AU", "NF" to "AU",
    )

    /**
     * IANA time-zone id -> ISO 3166-1 alpha-2 country code. Ported from Loupe's
     * TimeZoneCountries table (sourced from the public zone.tab distribution).
     */
    private val TZ_COUNTRIES: Map<String, String> = mapOf(
        "Africa/Abidjan" to "CI", "Africa/Accra" to "GH", "Africa/Addis_Ababa" to "ET",
        "Africa/Algiers" to "DZ", "Africa/Asmara" to "ER", "Africa/Bamako" to "ML",
        "Africa/Bangui" to "CF", "Africa/Banjul" to "GM", "Africa/Bissau" to "GW",
        "Africa/Blantyre" to "MW", "Africa/Brazzaville" to "CG", "Africa/Bujumbura" to "BI",
        "Africa/Cairo" to "EG", "Africa/Casablanca" to "MA", "Africa/Ceuta" to "ES",
        "Africa/Conakry" to "GN", "Africa/Dakar" to "SN", "Africa/Dar_es_Salaam" to "TZ",
        "Africa/Djibouti" to "DJ", "Africa/Douala" to "CM", "Africa/El_Aaiun" to "EH",
        "Africa/Freetown" to "SL", "Africa/Gaborone" to "BW", "Africa/Harare" to "ZW",
        "Africa/Johannesburg" to "ZA", "Africa/Juba" to "SS", "Africa/Kampala" to "UG",
        "Africa/Khartoum" to "SD", "Africa/Kigali" to "RW", "Africa/Kinshasa" to "CD",
        "Africa/Lagos" to "NG", "Africa/Libreville" to "GA", "Africa/Lome" to "TG",
        "Africa/Luanda" to "AO", "Africa/Lubumbashi" to "CD", "Africa/Lusaka" to "ZM",
        "Africa/Malabo" to "GQ", "Africa/Maputo" to "MZ", "Africa/Maseru" to "LS",
        "Africa/Mbabane" to "SZ", "Africa/Mogadishu" to "SO", "Africa/Monrovia" to "LR",
        "Africa/Nairobi" to "KE", "Africa/Ndjamena" to "TD", "Africa/Niamey" to "NE",
        "Africa/Nouakchott" to "MR", "Africa/Ouagadougou" to "BF", "Africa/Porto-Novo" to "BJ",
        "Africa/Sao_Tome" to "ST", "Africa/Tripoli" to "LY", "Africa/Tunis" to "TN",
        "Africa/Windhoek" to "NA",

        "America/Adak" to "US", "America/Anchorage" to "US", "America/Anguilla" to "AI",
        "America/Antigua" to "AG", "America/Araguaina" to "BR", "America/Argentina/Buenos_Aires" to "AR",
        "America/Argentina/Catamarca" to "AR", "America/Argentina/Cordoba" to "AR",
        "America/Argentina/Jujuy" to "AR", "America/Argentina/La_Rioja" to "AR",
        "America/Argentina/Mendoza" to "AR", "America/Argentina/Rio_Gallegos" to "AR",
        "America/Argentina/Salta" to "AR", "America/Argentina/San_Juan" to "AR",
        "America/Argentina/San_Luis" to "AR", "America/Argentina/Tucuman" to "AR",
        "America/Argentina/Ushuaia" to "AR", "America/Aruba" to "AW", "America/Asuncion" to "PY",
        "America/Atikokan" to "CA", "America/Bahia" to "BR", "America/Bahia_Banderas" to "MX",
        "America/Barbados" to "BB", "America/Belem" to "BR", "America/Belize" to "BZ",
        "America/Blanc-Sablon" to "CA", "America/Boa_Vista" to "BR", "America/Bogota" to "CO",
        "America/Boise" to "US", "America/Cambridge_Bay" to "CA", "America/Campo_Grande" to "BR",
        "America/Cancun" to "MX", "America/Caracas" to "VE", "America/Cayenne" to "GF",
        "America/Cayman" to "KY", "America/Chicago" to "US", "America/Chihuahua" to "MX",
        "America/Costa_Rica" to "CR", "America/Creston" to "CA", "America/Cuiaba" to "BR",
        "America/Curacao" to "CW", "America/Danmarkshavn" to "GL", "America/Dawson" to "CA",
        "America/Dawson_Creek" to "CA", "America/Denver" to "US", "America/Detroit" to "US",
        "America/Dominica" to "DM", "America/Edmonton" to "CA", "America/Eirunepe" to "BR",
        "America/El_Salvador" to "SV", "America/Fort_Nelson" to "CA", "America/Fortaleza" to "BR",
        "America/Glace_Bay" to "CA", "America/Goose_Bay" to "CA", "America/Grand_Turk" to "TC",
        "America/Grenada" to "GD", "America/Guadeloupe" to "GP", "America/Guatemala" to "GT",
        "America/Guayaquil" to "EC", "America/Guyana" to "GY", "America/Halifax" to "CA",
        "America/Havana" to "CU", "America/Hermosillo" to "MX", "America/Indiana/Indianapolis" to "US",
        "America/Indiana/Knox" to "US", "America/Indiana/Marengo" to "US",
        "America/Indiana/Petersburg" to "US", "America/Indiana/Tell_City" to "US",
        "America/Indiana/Vevay" to "US", "America/Indiana/Vincennes" to "US",
        "America/Indiana/Winamac" to "US", "America/Inuvik" to "CA", "America/Iqaluit" to "CA",
        "America/Jamaica" to "JM", "America/Juneau" to "US", "America/Kentucky/Louisville" to "US",
        "America/Kentucky/Monticello" to "US", "America/Kralendijk" to "BQ", "America/La_Paz" to "BO",
        "America/Lima" to "PE", "America/Los_Angeles" to "US", "America/Lower_Princes" to "SX",
        "America/Maceio" to "BR", "America/Managua" to "NI", "America/Manaus" to "BR",
        "America/Marigot" to "MF", "America/Martinique" to "MQ", "America/Matamoros" to "MX",
        "America/Mazatlan" to "MX", "America/Menominee" to "US", "America/Merida" to "MX",
        "America/Metlakatla" to "US", "America/Mexico_City" to "MX", "America/Miquelon" to "PM",
        "America/Moncton" to "CA", "America/Monterrey" to "MX", "America/Montevideo" to "UY",
        "America/Montserrat" to "MS", "America/Nassau" to "BS", "America/New_York" to "US",
        "America/Nome" to "US", "America/Noronha" to "BR", "America/North_Dakota/Beulah" to "US",
        "America/North_Dakota/Center" to "US", "America/North_Dakota/New_Salem" to "US",
        "America/Nuuk" to "GL", "America/Ojinaga" to "MX", "America/Panama" to "PA",
        "America/Paramaribo" to "SR", "America/Phoenix" to "US", "America/Port-au-Prince" to "HT",
        "America/Port_of_Spain" to "TT", "America/Porto_Velho" to "BR", "America/Puerto_Rico" to "PR",
        "America/Punta_Arenas" to "CL", "America/Rankin_Inlet" to "CA", "America/Recife" to "BR",
        "America/Regina" to "CA", "America/Resolute" to "CA", "America/Rio_Branco" to "BR",
        "America/Santarem" to "BR", "America/Santiago" to "CL", "America/Santo_Domingo" to "DO",
        "America/Sao_Paulo" to "BR", "America/Scoresbysund" to "GL", "America/Sitka" to "US",
        "America/St_Barthelemy" to "BL", "America/St_Johns" to "CA", "America/St_Kitts" to "KN",
        "America/St_Lucia" to "LC", "America/St_Thomas" to "VI", "America/St_Vincent" to "VC",
        "America/Swift_Current" to "CA", "America/Tegucigalpa" to "HN", "America/Thule" to "GL",
        "America/Tijuana" to "MX", "America/Toronto" to "CA", "America/Tortola" to "VG",
        "America/Vancouver" to "CA", "America/Whitehorse" to "CA", "America/Winnipeg" to "CA",
        "America/Yakutat" to "US",

        "Antarctica/Casey" to "AQ", "Antarctica/Davis" to "AQ", "Antarctica/DumontDUrville" to "AQ",
        "Antarctica/Macquarie" to "AU", "Antarctica/Mawson" to "AQ", "Antarctica/McMurdo" to "AQ",
        "Antarctica/Palmer" to "AQ", "Antarctica/Rothera" to "AQ", "Antarctica/Syowa" to "AQ",
        "Antarctica/Troll" to "AQ", "Antarctica/Vostok" to "AQ",

        "Arctic/Longyearbyen" to "SJ",

        "Asia/Aden" to "YE", "Asia/Almaty" to "KZ", "Asia/Amman" to "JO", "Asia/Anadyr" to "RU",
        "Asia/Aqtau" to "KZ", "Asia/Aqtobe" to "KZ", "Asia/Ashgabat" to "TM", "Asia/Atyrau" to "KZ",
        "Asia/Baghdad" to "IQ", "Asia/Bahrain" to "BH", "Asia/Baku" to "AZ", "Asia/Bangkok" to "TH",
        "Asia/Barnaul" to "RU", "Asia/Beirut" to "LB", "Asia/Bishkek" to "KG", "Asia/Brunei" to "BN",
        "Asia/Chita" to "RU", "Asia/Choibalsan" to "MN", "Asia/Colombo" to "LK", "Asia/Damascus" to "SY",
        "Asia/Dhaka" to "BD", "Asia/Dili" to "TL", "Asia/Dubai" to "AE", "Asia/Dushanbe" to "TJ",
        "Asia/Famagusta" to "CY", "Asia/Gaza" to "PS", "Asia/Hebron" to "PS", "Asia/Ho_Chi_Minh" to "VN",
        "Asia/Hong_Kong" to "HK", "Asia/Hovd" to "MN", "Asia/Irkutsk" to "RU", "Asia/Jakarta" to "ID",
        "Asia/Jayapura" to "ID", "Asia/Jerusalem" to "IL", "Asia/Kabul" to "AF", "Asia/Kamchatka" to "RU",
        "Asia/Karachi" to "PK", "Asia/Kathmandu" to "NP", "Asia/Khandyga" to "RU", "Asia/Kolkata" to "IN",
        "Asia/Krasnoyarsk" to "RU", "Asia/Kuala_Lumpur" to "MY", "Asia/Kuching" to "MY",
        "Asia/Kuwait" to "KW", "Asia/Macau" to "MO", "Asia/Magadan" to "RU", "Asia/Makassar" to "ID",
        "Asia/Manila" to "PH", "Asia/Muscat" to "OM", "Asia/Nicosia" to "CY", "Asia/Novokuznetsk" to "RU",
        "Asia/Novosibirsk" to "RU", "Asia/Omsk" to "RU", "Asia/Oral" to "KZ", "Asia/Phnom_Penh" to "KH",
        "Asia/Pontianak" to "ID", "Asia/Pyongyang" to "KP", "Asia/Qatar" to "QA", "Asia/Qostanay" to "KZ",
        "Asia/Qyzylorda" to "KZ", "Asia/Riyadh" to "SA", "Asia/Sakhalin" to "RU", "Asia/Samarkand" to "UZ",
        "Asia/Seoul" to "KR", "Asia/Shanghai" to "CN", "Asia/Singapore" to "SG", "Asia/Srednekolymsk" to "RU",
        "Asia/Taipei" to "TW", "Asia/Tashkent" to "UZ", "Asia/Tbilisi" to "GE", "Asia/Tehran" to "IR",
        "Asia/Thimphu" to "BT", "Asia/Tokyo" to "JP", "Asia/Tomsk" to "RU", "Asia/Ulaanbaatar" to "MN",
        "Asia/Urumqi" to "CN", "Asia/Ust-Nera" to "RU", "Asia/Vientiane" to "LA", "Asia/Vladivostok" to "RU",
        "Asia/Yakutsk" to "RU", "Asia/Yangon" to "MM", "Asia/Yekaterinburg" to "RU", "Asia/Yerevan" to "AM",

        "Atlantic/Azores" to "PT", "Atlantic/Bermuda" to "BM", "Atlantic/Canary" to "ES",
        "Atlantic/Cape_Verde" to "CV", "Atlantic/Faroe" to "FO", "Atlantic/Madeira" to "PT",
        "Atlantic/Reykjavik" to "IS", "Atlantic/South_Georgia" to "GS", "Atlantic/St_Helena" to "SH",
        "Atlantic/Stanley" to "FK",

        "Australia/Adelaide" to "AU", "Australia/Brisbane" to "AU", "Australia/Broken_Hill" to "AU",
        "Australia/Darwin" to "AU", "Australia/Eucla" to "AU", "Australia/Hobart" to "AU",
        "Australia/Lindeman" to "AU", "Australia/Lord_Howe" to "AU", "Australia/Melbourne" to "AU",
        "Australia/Perth" to "AU", "Australia/Sydney" to "AU",

        "Europe/Amsterdam" to "NL", "Europe/Andorra" to "AD", "Europe/Astrakhan" to "RU",
        "Europe/Athens" to "GR", "Europe/Belgrade" to "RS", "Europe/Berlin" to "DE",
        "Europe/Bratislava" to "SK", "Europe/Brussels" to "BE", "Europe/Bucharest" to "RO",
        "Europe/Budapest" to "HU", "Europe/Busingen" to "DE", "Europe/Chisinau" to "MD",
        "Europe/Copenhagen" to "DK", "Europe/Dublin" to "IE", "Europe/Gibraltar" to "GI",
        "Europe/Guernsey" to "GG", "Europe/Helsinki" to "FI", "Europe/Isle_of_Man" to "IM",
        "Europe/Istanbul" to "TR", "Europe/Jersey" to "JE", "Europe/Kaliningrad" to "RU",
        "Europe/Kirov" to "RU", "Europe/Kyiv" to "UA", "Europe/Lisbon" to "PT", "Europe/Ljubljana" to "SI",
        "Europe/London" to "GB", "Europe/Luxembourg" to "LU", "Europe/Madrid" to "ES",
        "Europe/Malta" to "MT", "Europe/Mariehamn" to "AX", "Europe/Minsk" to "BY",
        "Europe/Monaco" to "MC", "Europe/Moscow" to "RU", "Europe/Oslo" to "NO", "Europe/Paris" to "FR",
        "Europe/Podgorica" to "ME", "Europe/Prague" to "CZ", "Europe/Riga" to "LV",
        "Europe/Rome" to "IT", "Europe/Samara" to "RU", "Europe/San_Marino" to "SM",
        "Europe/Sarajevo" to "BA", "Europe/Saratov" to "RU", "Europe/Simferopol" to "RU",
        "Europe/Skopje" to "MK", "Europe/Sofia" to "BG", "Europe/Stockholm" to "SE",
        "Europe/Tallinn" to "EE", "Europe/Tirane" to "AL", "Europe/Ulyanovsk" to "RU",
        "Europe/Vaduz" to "LI", "Europe/Vatican" to "VA", "Europe/Vienna" to "AT",
        "Europe/Vilnius" to "LT", "Europe/Volgograd" to "RU", "Europe/Warsaw" to "PL",
        "Europe/Zagreb" to "HR", "Europe/Zurich" to "CH",

        "Indian/Antananarivo" to "MG", "Indian/Chagos" to "IO", "Indian/Christmas" to "CX",
        "Indian/Cocos" to "CC", "Indian/Comoro" to "KM", "Indian/Kerguelen" to "TF",
        "Indian/Mahe" to "SC", "Indian/Maldives" to "MV", "Indian/Mauritius" to "MU",
        "Indian/Mayotte" to "YT", "Indian/Reunion" to "RE",

        "Pacific/Apia" to "WS", "Pacific/Auckland" to "NZ", "Pacific/Bougainville" to "PG",
        "Pacific/Chatham" to "NZ", "Pacific/Chuuk" to "FM", "Pacific/Easter" to "CL",
        "Pacific/Efate" to "VU", "Pacific/Fakaofo" to "TK", "Pacific/Fiji" to "FJ",
        "Pacific/Funafuti" to "TV", "Pacific/Galapagos" to "EC", "Pacific/Gambier" to "PF",
        "Pacific/Guadalcanal" to "SB", "Pacific/Guam" to "GU", "Pacific/Honolulu" to "US",
        "Pacific/Kanton" to "KI", "Pacific/Kiritimati" to "KI", "Pacific/Kosrae" to "FM",
        "Pacific/Kwajalein" to "MH", "Pacific/Majuro" to "MH", "Pacific/Marquesas" to "PF",
        "Pacific/Midway" to "UM", "Pacific/Nauru" to "NR", "Pacific/Niue" to "NU",
        "Pacific/Norfolk" to "NF", "Pacific/Noumea" to "NC", "Pacific/Pago_Pago" to "AS",
        "Pacific/Palau" to "PW", "Pacific/Pitcairn" to "PN", "Pacific/Pohnpei" to "FM",
        "Pacific/Port_Moresby" to "PG", "Pacific/Rarotonga" to "CK", "Pacific/Saipan" to "MP",
        "Pacific/Tahiti" to "PF", "Pacific/Tarawa" to "KI", "Pacific/Tongatapu" to "TO",
        "Pacific/Wake" to "UM", "Pacific/Wallis" to "WF",
    )

    /**
     * Deprecated / legacy IANA zone ids that Android still returns, mapped to their
     * ISO 3166-1 alpha-2 country. Consulted only as a fallback after [TZ_COUNTRIES]
     * so a large population (e.g. Asia/Calcutta) still resolves to a country.
     */
    private val TZ_ALIASES: Map<String, String> = mapOf(
        "Asia/Calcutta" to "IN", "Asia/Rangoon" to "MM", "Asia/Saigon" to "VN",
        "Asia/Katmandu" to "NP", "Asia/Dacca" to "BD", "Asia/Thimbu" to "BT",
        "Asia/Ulan_Bator" to "MN", "Asia/Ashkhabad" to "TM", "Asia/Ujung_Pandang" to "ID",
        "Europe/Kiev" to "UA", "Europe/Uzhgorod" to "UA", "Europe/Zaporozhye" to "UA",
        "Europe/Nicosia" to "CY", "America/Buenos_Aires" to "AR", "America/Godthab" to "GL",
        "US/Pacific" to "US", "US/Eastern" to "US", "US/Central" to "US", "US/Mountain" to "US",
        "US/Hawaii" to "US", "US/Alaska" to "US", "US/Arizona" to "US", "GB" to "GB",
        "Asia/Istanbul" to "TR", "Africa/Asmera" to "ER", "Atlantic/Faeroe" to "FO",
    )
}
