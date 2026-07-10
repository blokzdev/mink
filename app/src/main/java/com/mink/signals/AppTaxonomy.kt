package com.mink.signals

/**
 * Pure, framework-free categorisation of installed package names into lifestyle
 * buckets. Kept off the Android APIs so the inference logic can be unit tested
 * on the plain JVM.
 *
 * The point Mink is making mirrors Loupe: the other apps on your phone hint at
 * your work, travel, finances, and hobbies. An app that can list your packages
 * can read that profile without ever asking a permission.
 */
internal object AppTaxonomy {

    /** A lifestyle bucket an installed app can fall into. */
    enum class AppCategory(val label: String) {
        FINANCE("finance"),
        CRYPTO("crypto"),
        SOCIAL("social"),
        MESSAGING("messaging"),
        DATING("dating"),
        HEALTH("health"),
        VPN("VPN"),
        SHOPPING("shopping"),
        TRAVEL("travel"),
    }

    /**
     * Known base package names per category. A package matches a base when it
     * equals the base or is a sub-package of it (base plus a dot), so
     * "com.tinder" matches "com.tinder" and "com.tinder.debug" but not an
     * unrelated "com.tinderbox.app".
     */
    private val TABLE: Map<AppCategory, List<String>> = mapOf(
        AppCategory.FINANCE to listOf(
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.chase.sig.android",
            "com.infonow.bofa",
            "com.wf.wellsfargomobile",
            "com.citi.citimobile",
            "com.konylabs.capitalone",
            "com.usaa.mobile.android.usaa",
            "com.robinhood.android",
            "com.etrade.mobilepro.activity",
            "com.fidelity.android",
            "com.schwab.mobile",
            "com.mint",
            "com.transferwise.android",
            "com.revolut.revolut",
            "co.mona.android",
            "com.monese.monese",
            "com.discover.mobile",
            "com.americanexpress.android.acctsvcs.us",
        ),
        AppCategory.CRYPTO to listOf(
            "com.coinbase.android",
            "com.binance.dev",
            "com.kraken.trade",
            "com.blockchain.wallet",
            "io.metamask",
            "co.mona.android.wallet",
            "org.toshi",
            "com.wirex",
            "com.bitcoin.mwallet",
            "piuk.blockchain.android",
            "com.crypto.defiwallet",
            "com.gemini.android.app",
        ),
        AppCategory.SOCIAL to listOf(
            "com.facebook.katana",
            "com.instagram.android",
            "com.twitter.android",
            "com.zhiliaoapp.musically",
            "com.snapchat.android",
            "com.reddit.frontpage",
            "com.linkedin.android",
            "com.pinterest",
            "com.tumblr",
            "com.bsky.app",
            "org.mastodon.android",
            "com.google.android.youtube",
        ),
        AppCategory.MESSAGING to listOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.viber.voip",
            "com.facebook.orca",
            "com.discord",
            "com.microsoft.teams",
            "jp.naver.line.android",
            "com.tencent.mm",
            "com.google.android.apps.messaging",
        ),
        AppCategory.DATING to listOf(
            "com.tinder",
            "com.bumble.app",
            "com.okcupid.okcupid",
            "com.match.android.matchmobile",
            "com.grindrapp.android",
            "co.hinge.app",
            "com.pof.android",
            "com.ftw_and_co.happn",
            "com.badoo.mobile",
        ),
        AppCategory.HEALTH to listOf(
            "com.myfitnesspal.android",
            "com.fitbit.FitbitMobile",
            "com.google.android.apps.fitness",
            "com.strava",
            "com.getsomeheadspace.android",
            "com.calm.android",
            "com.flo.period.tracker",
            "com.clue.android",
            "com.sec.android.app.shealth",
            "com.samsung.android.app.shealth",
            "com.weightwatchers.mobile",
            "com.eveningflower.womenlogcalendar",
        ),
        AppCategory.VPN to listOf(
            "com.nordvpn.android",
            "com.expressvpn.vpn",
            "com.protonvpn.android",
            "com.surfshark.vpnclient.android",
            "com.privateinternetaccess.android",
            "com.wireguard.android",
            "org.torproject.android",
            "hotspotshield.android.vpn",
            "com.mullvad.mullvadvpn",
        ),
        AppCategory.SHOPPING to listOf(
            "com.amazon.mShop.android.shopping",
            "com.ebay.mobile",
            "com.etsy.android",
            "com.zzkko",
            "com.contextlogic.wish",
            "com.walmart.android",
            "com.alibaba.aliexpresshd",
            "com.target.ui",
        ),
        AppCategory.TRAVEL to listOf(
            "com.ubercab",
            "com.lyft.android",
            "com.airbnb.android",
            "com.booking",
            "com.tripadvisor.tripadvisor",
            "com.kayak.android",
            "com.expedia.bookings",
            "com.google.android.apps.maps",
        ),
    )

    /**
     * Which categories a single package falls into. Most packages fall into
     * zero or one, but the return is a set so the table can overlap safely.
     */
    fun categorize(packageName: String): Set<AppCategory> {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return emptySet()
        val hits = LinkedHashSet<AppCategory>()
        for ((category, bases) in TABLE) {
            if (bases.any { matches(pkg, it.lowercase()) }) hits += category
        }
        return hits
    }

    /**
     * Aggregate a whole package list into a per-category count of matching apps.
     * Only categories with at least one match appear in the result, ordered by
     * the [AppCategory] declaration order.
     */
    fun profile(packageNames: Iterable<String>): Map<AppCategory, Int> {
        val counts = linkedMapOf<AppCategory, Int>()
        for (pkg in packageNames) {
            for (category in categorize(pkg)) {
                counts[category] = (counts[category] ?: 0) + 1
            }
        }
        // Re-order by enum declaration for a stable, readable profile.
        return AppCategory.entries
            .filter { counts.containsKey(it) }
            .associateWith { counts.getValue(it) }
    }

    private fun matches(pkg: String, base: String): Boolean =
        pkg == base || pkg.startsWith("$base.")
}
