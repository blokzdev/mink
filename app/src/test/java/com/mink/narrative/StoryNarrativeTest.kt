package com.mink.narrative

import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [StoryNarrative]: the derived "story" cards (travel,
 * gear-owner, region/SIM mismatch, uptime, device age, and app inferences) and
 * the two ported helpers ([StoryNarrative.ownerName],
 * [StoryNarrative.timeZoneCountry]).
 *
 * Snapshots are built by hand with [FingerprintSignal.make] so every id follows
 * the real "<category.id>.<key>" scheme, and the clock plus every app-access
 * reading are injected via [DeviceStoryContext]. That keeps each assertion
 * deterministic and exercises the exact strings the implementation emits, never
 * inventing an inference the inputs do not support.
 */
class StoryNarrativeTest {

    private val emptyContext =
        DeviceStoryContext(nowMs = 0L, earliestUserInstallMs = null, userPackageNames = emptyList())

    /** A single signal tagged with its category, ready for [snapshot]. */
    private fun sig(
        category: SignalCategory,
        key: String,
        value: String = "",
        entries: List<SignalEntry>? = null,
    ): Pair<SignalCategory, FingerprintSignal> =
        category to FingerprintSignal.make(
            key = key,
            category = category,
            name = key,
            value = value,
            rationale = "",
            entries = entries,
        )

    /** Groups tagged signals into the per-category snapshot the derivation reads. */
    private fun snapshot(
        vararg signals: Pair<SignalCategory, FingerprintSignal>,
    ): Map<SignalCategory, List<FingerprintSignal>> =
        signals.groupBy({ it.first }, { it.second })

    private fun countryEntry(iso: String) = listOf(SignalEntry("Country", iso))

    private fun cardOf(cards: List<StoryCard>, id: String): StoryCard? =
        cards.firstOrNull { it.id == id }

    // ---- travel ----

    @Test
    fun travelFiresWhenTimeZoneCountryDiffersFromRegion() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "timeZone", value = "America/New_York"),
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("GB")),
            ),
            emptyContext,
        )

        val travel = cardOf(cards, "travel")!!
        assertEquals("You may be travelling", travel.title)
        assertEquals(
            "Your time zone points to United States, but your phone's region is set to United Kingdom.",
            travel.body,
        )
        assertEquals(
            "Inferred from a time zone (America/New_York) that does not match your region setting.",
            travel.basis,
        )
        // No roaming signal, so the SIM sentence is absent.
        assertFalse(travel.body.contains("roaming"))
        // travel is the only card that fires from this snapshot.
        assertEquals(listOf("travel"), cards.map { it.id })
    }

    @Test
    fun travelIsNullWhenTimeZoneCountryMatchesRegion() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "timeZone", value = "America/New_York"),
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("US")),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "travel"))
    }

    @Test
    fun travelIsNullWhenTimeZoneIsUnknown() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "timeZone", value = "Foo/Bar"),
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("US")),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "travel"))
    }

    @Test
    fun travelAppendsSimSentenceWhenRoaming() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "timeZone", value = "America/New_York"),
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("GB")),
                sig(SignalCategory.TELEPHONY, "roaming", value = "true"),
            ),
            emptyContext,
        )

        val travel = cardOf(cards, "travel")!!
        assertTrue(travel.body.endsWith("Your SIM also reports roaming."))
    }

    @Test
    fun travelIsNullForATerritoryOnItsSovereignRegion() {
        // A Puerto Rico resident sets region to US: tz-country PR normalizes onto US.
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "timeZone", value = "America/Puerto_Rico"),
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("US")),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "travel"))
    }

    @Test
    fun travelFiresForALegacyTimeZoneAlias() {
        // Android still hands back the deprecated Asia/Calcutta id for India.
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "timeZone", value = "Asia/Calcutta"),
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("GB")),
            ),
            emptyContext,
        )

        val travel = cardOf(cards, "travel")!!
        assertTrue(travel.body.contains("India"))
        assertTrue(travel.body.contains("United Kingdom"))
    }

    @Test
    fun travelAndRegionSimAreNullForANumericRegion() {
        // es-419 yields the UN M.49 region "419", which is not a real country.
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "timeZone", value = "America/Mexico_City"),
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("419")),
                sig(SignalCategory.TELEPHONY, "country", entries = listOf(SignalEntry("SIM", "MX"))),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "travel"))
        assertNull(cardOf(cards, "regionSim"))
    }

    // ---- owner ----

    @Test
    fun ownerCardNamesTheOwnerFromAPossessiveDeviceName() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(
                    SignalCategory.BLUETOOTH,
                    "bonded",
                    entries = listOf(SignalEntry("Talal's AirPods", "LE Audio")),
                ),
            ),
            emptyContext,
        )

        val owner = cardOf(cards, "owner")!!
        assertEquals("Your gear may carry your name", owner.title)
        assertEquals(
            "A device paired to this phone is named \"Talal's AirPods\", so your name might be Talal.",
            owner.body,
        )
        assertEquals("Read from the name of a device paired to this phone.", owner.basis)
    }

    @Test
    fun ownerCardNamesTheOwnerFromTheConnectorForm() {
        val de = StoryNarrative.build(
            snapshot(
                sig(
                    SignalCategory.BLUETOOTH,
                    "bonded",
                    entries = listOf(SignalEntry("AirPods de Talal", "LE Audio")),
                ),
            ),
            emptyContext,
        )
        assertTrue(cardOf(de, "owner")!!.body.contains("your name might be Talal."))

        val possessive = StoryNarrative.build(
            snapshot(
                sig(
                    SignalCategory.BLUETOOTH,
                    "bonded",
                    entries = listOf(SignalEntry("John's iPhone", "LE Audio")),
                ),
            ),
            emptyContext,
        )
        assertTrue(cardOf(possessive, "owner")!!.body.contains("your name might be John."))
    }

    @Test
    fun ownerFallsBackForBrandsRoomWordsAndTruncatingCaptures() {
        // Each of these must reach the fallback card, never asserting a person's name.
        val fallbackNames = listOf(
            "我的Watch",              // CJK: no uppercase-initial capture
            "リビングのスピーカー",   // Katakana + particle: no capture
            "Chris Beats",            // bare genitive dropped, so no "Chri" truncation
            "JBL's Speaker",          // all-caps brand acronym rejected
            "Apple TV de Salón",      // connector capture "Salón" is a room word
        )
        for (deviceName in fallbackNames) {
            val cards = StoryNarrative.build(
                snapshot(
                    sig(
                        SignalCategory.BLUETOOTH,
                        "bonded",
                        entries = listOf(SignalEntry(deviceName, "Device")),
                    ),
                ),
                emptyContext,
            )
            val owner = cardOf(cards, "owner")!!
            assertEquals("Your gear is named after you or your things", owner.title)
            assertTrue(owner.body.contains("\"$deviceName\""))
            assertFalse(owner.body.contains("your name might be"))
        }
    }

    @Test
    fun ownerFallsBackWhenNoNameCanBeExtracted() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(
                    SignalCategory.BLUETOOTH,
                    "bonded",
                    entries = listOf(SignalEntry("Living Room Speaker", "Speaker")),
                ),
            ),
            emptyContext,
        )

        val owner = cardOf(cards, "owner")!!
        assertEquals("Your gear is named after you or your things", owner.title)
        assertTrue(owner.body.contains("\"Living Room Speaker\""))
        // The fallback never invents an owner name.
        assertFalse(owner.body.contains("your name might be"))
    }

    @Test
    fun ownerIsNullWithoutAnyBluetoothSignals() {
        val cards = StoryNarrative.build(snapshot(), emptyContext)
        assertNull(cardOf(cards, "owner"))
    }

    @Test
    fun ownerUsesTheAdapterNameWhenThereAreNoBondedEntries() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.BLUETOOTH, "name", value = "Talal's Beats"),
            ),
            emptyContext,
        )

        val owner = cardOf(cards, "owner")!!
        assertEquals("Your gear may carry your name", owner.title)
        assertTrue(owner.body.contains("\"Talal's Beats\""))
        assertTrue(owner.body.contains("your name might be Talal."))
    }

    // ---- regionSim ----

    @Test
    fun regionSimFiresWhenRegionAndSimCountryDiffer() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("US")),
                sig(SignalCategory.TELEPHONY, "country", entries = listOf(SignalEntry("SIM", "GB"))),
            ),
            emptyContext,
        )

        val regionSim = cardOf(cards, "regionSim")!!
        assertEquals("Your region and SIM do not match", regionSim.title)
        assertEquals(
            "Your phone's region is set to United States, but your SIM is from United Kingdom. " +
                "That mismatch is itself distinctive, and it can mean travel, a foreign SIM, or a " +
                "privacy setup.",
            regionSim.body,
        )
        assertEquals("Comparing your device region with your SIM's country.", regionSim.basis)
        // No time-zone signal, so regionSim is the only card here.
        assertEquals(listOf("regionSim"), cards.map { it.id })
    }

    @Test
    fun regionSimIsNullWhenRegionAndSimAreEqual() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("US")),
                sig(SignalCategory.TELEPHONY, "country", entries = listOf(SignalEntry("SIM", "US"))),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "regionSim"))
    }

    @Test
    fun regionSimIsNullWhenSimIsAbsent() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("US")),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "regionSim"))
    }

    // ---- uptime ----

    @Test
    fun uptimeCardMentionsTheDurationAndBootTime() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.SYSTEM_INFO, "uptime", value = "3 days, 4 hours"),
                sig(SignalCategory.SYSTEM_INFO, "bootTime", value = "2026-07-13 08:00:00"),
            ),
            emptyContext,
        )

        val uptime = cardOf(cards, "uptime")!!
        assertEquals("This phone has been running a while", uptime.title)
        assertEquals(
            "It has been up for 3 days, 4 hours, since 2026-07-13 08:00:00. Two apps that read this " +
                "agree to the second, which links them to the same phone.",
            uptime.body,
        )
        assertEquals("Read from the system boot time, which any app can see.", uptime.basis)
    }

    @Test
    fun uptimeIsNullWhenAbsent() {
        val cards = StoryNarrative.build(snapshot(), emptyContext)
        assertNull(cardOf(cards, "uptime"))
    }

    // ---- birthday ----

    @Test
    fun birthdayCardCarriesTheMonthYearAndApproxYears() {
        val earliest = 1_500_000_000_000L // 2017-07-14T02:40:00Z
        val context = DeviceStoryContext(
            nowMs = earliest + 3 * YEAR_MS,
            earliestUserInstallMs = earliest,
            userPackageNames = emptyList(),
        )

        val birthday = cardOf(StoryNarrative.build(snapshot(), context), "birthday")!!
        assertEquals("This phone has been yours for a while", birthday.title)
        assertTrue(birthday.body.contains("July 2017"))
        assertTrue(birthday.body.contains("about 3 years ago"))
        assertEquals("Inferred from the oldest app installed on this phone.", birthday.basis)
    }

    @Test
    fun birthdayUsesTheSingularYearAtExactlyOneYear() {
        val earliest = 1_500_000_000_000L
        val context = DeviceStoryContext(
            nowMs = earliest + YEAR_MS,
            earliestUserInstallMs = earliest,
            userPackageNames = emptyList(),
        )

        val body = cardOf(StoryNarrative.build(snapshot(), context), "birthday")!!.body
        assertTrue(body.contains("about 1 year ago"))
        assertFalse(body.contains("about 1 years ago"))
    }

    @Test
    fun birthdayOmitsTheApproxYearsUnderHalfAYear() {
        val earliest = 1_500_000_000_000L
        val context = DeviceStoryContext(
            // A quarter-year rounds to zero, so the "about N years" clause is omitted.
            nowMs = earliest + YEAR_MS / 4,
            earliestUserInstallMs = earliest,
            userPackageNames = emptyList(),
        )

        val body = cardOf(StoryNarrative.build(snapshot(), context), "birthday")!!.body
        assertTrue(body.contains("July 2017"))
        assertFalse(body.contains("about"))
    }

    @Test
    fun birthdayRoundsApproxYearsToTheNearest() {
        val now = 1_700_000_000_000L
        val earliest = now - (1.9 * YEAR_MS).toLong()
        val context = DeviceStoryContext(
            nowMs = now,
            earliestUserInstallMs = earliest,
            userPackageNames = emptyList(),
        )

        val body = cardOf(StoryNarrative.build(snapshot(), context), "birthday")!!.body
        assertTrue(body.contains("about 2 years ago"))
    }

    @Test
    fun birthdayIsNullWhenEarliestInstallIsUnknown() {
        val cards = StoryNarrative.build(snapshot(), emptyContext)
        assertNull(cardOf(cards, "birthday"))
    }

    @Test
    fun birthdayIsNullWhenEarliestInstallIsImplausible() {
        // A bogus firstInstallMs of 0 (epoch) must not date the phone to 1970.
        val context = DeviceStoryContext(
            nowMs = 1_700_000_000_000L,
            earliestUserInstallMs = 0L,
            userPackageNames = emptyList(),
        )

        assertNull(cardOf(StoryNarrative.build(snapshot(), context), "birthday"))
    }

    @Test
    fun birthdayFiresForAPlausibleInstallFiveYearsBack() {
        val now = 1_700_000_000_000L
        val earliest = now - 5 * YEAR_MS
        val context = DeviceStoryContext(
            nowMs = now,
            earliestUserInstallMs = earliest,
            userPackageNames = emptyList(),
        )

        val body = cardOf(StoryNarrative.build(snapshot(), context), "birthday")!!.body
        assertTrue(body.contains("about 5 years ago"))
    }

    // ---- apps ----

    @Test
    fun appsCardJoinsPhrasesInCategoryOrder() {
        val context = DeviceStoryContext(
            nowMs = 0L,
            earliestUserInstallMs = null,
            userPackageNames = listOf(
                "com.coinbase.android",   // CRYPTO
                "com.facebook.katana",    // SOCIAL
                "com.instagram.android",  // SOCIAL
                "com.twitter.android",    // SOCIAL
                "com.tinder",             // DATING
            ),
        )

        val apps = cardOf(StoryNarrative.build(snapshot(), context), "apps")!!
        assertEquals("What your apps hint at", apps.title)
        assertEquals(
            "The mix of apps you have installed suggests you may hold crypto, you may be a heavy " +
                "social-media user, and you may be dating. Any app that can list your packages reads " +
                "this without a prompt.",
            apps.body,
        )
        assertEquals("Inferred from the apps installed on this phone.", apps.basis)
        assertTrue(apps.body.contains("crypto"))
        assertTrue(apps.body.contains("heavy social-media"))
        assertTrue(apps.body.contains("dating"))
    }

    @Test
    fun appsCardCapsAtFourPhrases() {
        val context = DeviceStoryContext(
            nowMs = 0L,
            earliestUserInstallMs = null,
            userPackageNames = listOf(
                "com.coinbase.android",   // CRYPTO
                "com.facebook.katana",    // SOCIAL
                "com.instagram.android",  // SOCIAL
                "com.twitter.android",    // SOCIAL
                "com.tinder",             // DATING
                "com.strava",             // HEALTH
                "com.nordvpn.android",    // VPN (fifth category, dropped by the cap)
            ),
        )

        val body = cardOf(StoryNarrative.build(snapshot(), context), "apps")!!.body
        // The first four categories in declaration order survive; VPN is the fifth and is dropped.
        assertTrue(body.contains("you may hold crypto"))
        assertTrue(body.contains("you may be a heavy social-media user"))
        assertTrue(body.contains("you may be dating"))
        assertTrue(body.contains("you may track your health"))
        assertFalse(body.contains("you use a VPN"))
    }

    @Test
    fun appsIsNullWhenNoPackagesFire() {
        val cards = StoryNarrative.build(snapshot(), emptyContext)
        assertNull(cardOf(cards, "apps"))
    }

    // ---- languages ----

    @Test
    fun languagesFiresWhenTwoDistinctLanguagesArePreferred() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(
                    SignalCategory.LOCALE,
                    "preferredLocales",
                    entries = listOf(SignalEntry("en-US", ""), SignalEntry("fr-FR", "")),
                ),
            ),
            emptyContext,
        )

        val languages = cardOf(cards, "languages")!!
        assertEquals("You use more than one language", languages.title)
        assertEquals(
            "Your preferred languages are English and French — that ordered set is often unique to you.",
            languages.body,
        )
        assertEquals("Read from your preferred languages.", languages.basis)
    }

    @Test
    fun languagesIsNullWithOnlyOneLanguage() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(
                    SignalCategory.LOCALE,
                    "preferredLocales",
                    entries = listOf(SignalEntry("en-US", "")),
                ),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "languages"))
    }

    @Test
    fun languagesIsNullWhenLocalesShareAPrimarySubtag() {
        // en-US and en-GB collapse onto the single primary subtag "en".
        val cards = StoryNarrative.build(
            snapshot(
                sig(
                    SignalCategory.LOCALE,
                    "preferredLocales",
                    entries = listOf(SignalEntry("en-US", ""), SignalEntry("en-GB", "")),
                ),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "languages"))
    }

    // ---- accessibility ----

    @Test
    fun accessibilityFiresAndListsTheEnabledFacets() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.ACCESSIBILITY, "touchExploration", value = "true"),
                sig(
                    SignalCategory.ACCESSIBILITY,
                    "displayFlags",
                    entries = listOf(
                        SignalEntry("High contrast text", "true"),
                        SignalEntry("Color inversion", "true"),
                    ),
                ),
            ),
            emptyContext,
        )

        val accessibility = cardOf(cards, "accessibility")!!
        assertEquals("You have accessibility settings on", accessibility.title)
        assertEquals(
            "Mink can see you use explore by touch, high-contrast text, and colour inversion. Any app " +
                "can read these flags with no prompt, and each one you change is a distinguishing detail.",
            accessibility.body,
        )
        assertEquals("Read from accessibility flags any app can check.", accessibility.basis)
    }

    @Test
    fun accessibilityFiresOnACustomAnimationScaleAndFontScale() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(
                    SignalCategory.ACCESSIBILITY,
                    "animationScales",
                    entries = listOf(SignalEntry("Window", "0.5")),
                ),
                sig(SignalCategory.ACCESSIBILITY, "fontScale", value = "1.15"),
            ),
            emptyContext,
        )

        val accessibility = cardOf(cards, "accessibility")!!
        assertEquals(
            "Mink can see you use reduced or custom animation and a larger or smaller text size. Any " +
                "app can read these flags with no prompt, and each one you change is a distinguishing " +
                "detail.",
            accessibility.body,
        )
    }

    @Test
    fun accessibilityIsNullWhenEveryFacetIsDefault() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.ACCESSIBILITY, "touchExploration", value = "false"),
                sig(
                    SignalCategory.ACCESSIBILITY,
                    "displayFlags",
                    entries = listOf(
                        SignalEntry("High contrast text", "false"),
                        SignalEntry("Color inversion", "false"),
                    ),
                ),
                sig(
                    SignalCategory.ACCESSIBILITY,
                    "animationScales",
                    entries = listOf(SignalEntry("Window", "1.0")),
                ),
                sig(SignalCategory.ACCESSIBILITY, "fontScale", value = "1.0"),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "accessibility"))
    }

    // ---- regionSettings ----

    @Test
    fun regionSettingsFiresOnAFirstDayOfWeekMismatch() {
        // The US region baseline starts its week on Sunday, so a Monday start
        // mismatches. First-day-of-week is the one facet a region reliably drives;
        // the clock default is language- not region-driven and is not compared.
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("US")),
                sig(SignalCategory.LOCALE, "firstDayOfWeek", value = "Monday"),
            ),
            emptyContext,
        )

        val region = cardOf(cards, "regionSettings")!!
        assertEquals("Your settings do not all match your region", region.title)
        assertEquals(
            "Your region is United States, but your week starts on Monday instead of Sunday — a small " +
                "mismatch that itself stands out.",
            region.body,
        )
        assertEquals("Comparing your settings with your region's defaults.", region.basis)
    }

    @Test
    fun regionSettingsIsNullWhenTheFirstDayMatchesTheRegion() {
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("US")),
                sig(SignalCategory.LOCALE, "firstDayOfWeek", value = "Sunday"),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "regionSettings"))
    }

    @Test
    fun regionSettingsIsNullWhenTheRegionIsAbsent() {
        // Without a country there is no region baseline to compare against.
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "firstDayOfWeek", value = "Monday"),
                sig(SignalCategory.LOCALE, "clock", value = "24-hour"),
            ),
            emptyContext,
        )

        assertNull(cardOf(cards, "regionSettings"))
    }

    // ---- build(): ordering and suppression ----

    @Test
    fun buildEmitsEveryCardInFixedOrder() {
        val earliest = 1_500_000_000_000L
        val context = DeviceStoryContext(
            nowMs = earliest + 3 * YEAR_MS,
            earliestUserInstallMs = earliest,
            userPackageNames = listOf(
                "com.coinbase.android",
                "com.facebook.katana",
                "com.instagram.android",
                "com.twitter.android",
                "com.tinder",
            ),
        )
        val cards = StoryNarrative.build(
            snapshot(
                sig(SignalCategory.LOCALE, "timeZone", value = "America/New_York"),
                sig(SignalCategory.LOCALE, "components", entries = countryEntry("GB")),
                sig(SignalCategory.TELEPHONY, "country", entries = listOf(SignalEntry("SIM", "FR"))),
                sig(
                    SignalCategory.BLUETOOTH,
                    "bonded",
                    entries = listOf(SignalEntry("Talal's AirPods", "LE Audio")),
                ),
                sig(SignalCategory.SYSTEM_INFO, "uptime", value = "3 days"),
                sig(SignalCategory.SYSTEM_INFO, "bootTime", value = "2026-07-13 08:00:00"),
            ),
            context,
        )

        assertEquals(
            listOf("travel", "owner", "regionSim", "uptime", "birthday", "apps"),
            cards.map { it.id },
        )
        // Calm copy throughout: no exclamation marks anywhere.
        cards.forEach { card ->
            assertFalse(card.title.contains("!"))
            assertFalse(card.body.contains("!"))
            assertFalse(card.basis.contains("!"))
        }
    }

    @Test
    fun buildReturnsEmptyWhenEveryInputIsAbsent() {
        assertTrue(StoryNarrative.build(snapshot(), emptyContext).isEmpty())
    }

    // ---- ported helpers, unit-tested directly ----

    @Test
    fun ownerNameExtractsAcrossTheSupportedForms() {
        assertEquals("Talal", StoryNarrative.ownerName("Talal's AirPods"))
        assertEquals("Talal", StoryNarrative.ownerName("AirPods de Talal"))
        assertEquals("John", StoryNarrative.ownerName("John's iPhone"))
        assertNull(StoryNarrative.ownerName("Living Room Speaker"))
        assertNull(StoryNarrative.ownerName(""))
    }

    @Test
    fun ownerNameRejectsBrandsRoomWordsAndTruncations() {
        assertNull(StoryNarrative.ownerName("我的Watch"))
        assertNull(StoryNarrative.ownerName("Chris Beats"))
        assertNull(StoryNarrative.ownerName("JBL's Speaker"))
        assertNull(StoryNarrative.ownerName("リビングのスピーカー"))
        assertNull(StoryNarrative.ownerName("Apple TV de Salón"))
    }

    @Test
    fun timeZoneCountryMapsKnownZonesAliasesAndRejectsUnknown() {
        assertEquals("US", StoryNarrative.timeZoneCountry("America/New_York"))
        assertEquals("GB", StoryNarrative.timeZoneCountry("Europe/London"))
        assertEquals("JP", StoryNarrative.timeZoneCountry("Asia/Tokyo"))
        assertEquals("IN", StoryNarrative.timeZoneCountry("Asia/Calcutta"))
        assertNull(StoryNarrative.timeZoneCountry("Foo/Bar"))
    }

    private companion object {
        /** Mirrors StoryNarrative's own coarse year length for the birthday fixtures. */
        private const val YEAR_MS = 31_536_000_000L
    }
}
