package com.mink.guardian

import com.mink.core.model.FingerprintSignal
import com.mink.core.model.Sensitivity
import com.mink.core.model.SignalCategory

/**
 * The deterministic guardian. Given the current signal snapshot it produces
 * plain-English findings with no model at all, so the guardian is useful on
 * every device and while the model is still downloading. It also answers chat
 * questions with templated replies when the LLM is unavailable.
 *
 * Pure and side-effect free, so it is fully unit testable. The controller turns
 * [RuleFinding]s into persisted [GuardianAlert]s.
 */
class RulesEngine {

    /** A finding before it is given an id and timestamp by the controller. */
    data class RuleFinding(
        val level: AlertLevel,
        val title: String,
        val body: String,
        val categoryId: String?,
        /** Stable key so the same finding does not alert twice. */
        val key: String,
    )

    /**
     * Evaluate the snapshot and return findings, most severe first. A category
     * is treated as exposed when it carries at least one collected signal (the
     * store only populates permissioned categories once granted and read).
     */
    fun evaluate(snapshot: Map<SignalCategory, List<FingerprintSignal>>): List<RuleFinding> {
        val findings = mutableListOf<RuleFinding>()

        if (exposed(snapshot, SignalCategory.LOCATION)) {
            findings += RuleFinding(
                level = AlertLevel.WARNING,
                title = "Location is exposed",
                body = "Any app you grant location can read your coordinates. Altitude " +
                    "alone can pin which floor of a building you are on.",
                categoryId = SignalCategory.LOCATION.id,
                key = "rule.location.exposed",
            )
        }

        val appCount = countFrom(snapshot, SignalCategory.INSTALLED_APPS)
        if (appCount != null) {
            findings += RuleFinding(
                level = AlertLevel.SUGGESTION,
                title = "Your installed apps form a profile",
                body = "You have $appCount apps installed. The exact set hints at your " +
                    "work, travel, finances, and hobbies, and it rarely matches anyone else.",
                categoryId = SignalCategory.INSTALLED_APPS.id,
                key = "rule.apps.profile",
            )
        }

        if (exposed(snapshot, SignalCategory.ACCOUNTS)) {
            findings += RuleFinding(
                level = AlertLevel.SUGGESTION,
                title = "Account types reveal your services",
                body = "The set of account types on this device shows which services you " +
                    "use, without needing a single username.",
                categoryId = SignalCategory.ACCOUNTS.id,
                key = "rule.accounts.services",
            )
        }

        if (exposed(snapshot, SignalCategory.WEB_VIEW_FINGERPRINT) ||
            exposed(snapshot, SignalCategory.GPU)
        ) {
            findings += RuleFinding(
                level = AlertLevel.INFO,
                title = "You render a unique fingerprint",
                body = "Your GPU and canvas output form a browser-style fingerprint. It is " +
                    "highly identifying and any web page can read it with no prompt.",
                categoryId = SignalCategory.WEB_VIEW_FINGERPRINT.id,
                key = "rule.render.fingerprint",
            )
        }

        if (clipboardHasContent(snapshot)) {
            findings += RuleFinding(
                level = AlertLevel.SUGGESTION,
                title = "Your clipboard holds content",
                body = "The clipboard currently holds content. Apps you open can read its " +
                    "type, and older versions of Android let them read the text itself.",
                categoryId = SignalCategory.CLIPBOARD.id,
                key = "rule.clipboard.content",
            )
        }

        if (exposed(snapshot, SignalCategory.DEVICE_IDENTITY)) {
            findings += RuleFinding(
                level = AlertLevel.INFO,
                title = "Your device identity is readable",
                body = "Any app can read your model, brand, and a stable id without asking. " +
                    "Trackers do not need your name to recognise you.",
                categoryId = SignalCategory.DEVICE_IDENTITY.id,
                key = "rule.identity.readable",
            )
        }

        return findings.sortedByDescending { it.level.ordinal }
    }

    /**
     * A templated reply for chat when no model is loaded. Keeps the guardian's
     * calm, explanatory voice and stays grounded in the current snapshot.
     */
    fun answer(
        question: String,
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
        summary: BaselineSummary? = null,
    ): String {
        val q = question.lowercase()
        if (mentionsLearning(q)) {
            learningAnswer(summary)?.let { return it }
        }
        return when {
            q.contains("location") || q.contains("gps") ->
                if (exposed(snapshot, SignalCategory.LOCATION)) {
                    "Location is currently exposed. Any app you grant it can read your " +
                        "coordinates, and altitude can even pin your floor. You can revoke " +
                        "it in Settings for apps that do not need it."
                } else {
                    "Location is not being read right now. If you grant it to an app, that " +
                        "app can read your coordinates and movement until you revoke it."
                }

            q.contains("app") || q.contains("install") -> {
                val n = countFrom(snapshot, SignalCategory.INSTALLED_APPS)
                if (n != null) {
                    "You have $n apps installed. The exact set hints at your work, travel, " +
                        "finances, and hobbies, and it rarely matches anyone else."
                } else {
                    "The list of apps you have installed is one of the strongest profiles a " +
                        "tracker can build. The exact set is close to unique to you."
                }
            }

            q.contains("fingerprint") || q.contains("unique") || q.contains("track") ->
                "Trackers do not need your name, email, or location to recognise you online. " +
                    "Your device model, screen, fonts, and GPU together form a fingerprint " +
                    "that is close to unique. I read these locally and never send them off " +
                    "this device."

            q.contains("model") || q.contains("download") ->
                "I can run a small language model fully on this device to explain what I see. " +
                    "Until it is downloaded I answer from a fixed set of rules, and nothing " +
                    "ever leaves your phone either way."

            else -> {
                val exposedCount = SignalCategory.entries.count { exposed(snapshot, it) }
                "I read $exposedCount surfaces on this device so far. The most identifying " +
                    "ones are your installed apps, your device identity, and your render " +
                    "fingerprint. Ask me about any of them and I will explain what it leaks."
            }
        }
    }

    // ---- helpers ----

    private fun mentionsLearning(q: String): Boolean =
        q.contains("learn") || q.contains("pattern") || q.contains("trend") ||
            q.contains("rhythm") || q.contains("baseline")

    /**
     * Answer a learning question from the [BaselineSummary], or null to fall
     * through to the general replies (no baseline yet). Uses the wall clock only
     * to phrase "for D days".
     */
    private fun learningAnswer(summary: BaselineSummary?): String? {
        if (summary == null) return null
        if (!summary.isMature) {
            return "I am still learning this device: ${summary.sweepCount} of " +
                "$MIN_SWEEPS_FOR_LEARNING sweeps so far. Give me a little more time and I can " +
                "describe its rhythms."
        }
        val duration = learningDurationPhrase(summary.learningSinceMs, System.currentTimeMillis())
        return buildString {
            append(
                "I have been watching ${summary.trackedSignals} signals $duration across " +
                    "${summary.sweepCount} sweeps. ",
            )
            append(
                "${summary.stableAnchors} are stable anchors and ${summary.expectedVolatile} " +
                    "change naturally. ",
            )
            val drifting = summary.driftingSignals.take(3)
            if (drifting.isNotEmpty()) {
                append(
                    "Lately these keep changing: " +
                        drifting.joinToString(", ") { "${it.name} (${it.recentChanges}x this week)" } +
                        ".",
                )
            } else {
                append("Nothing is drifting unusually right now.")
            }
        }
    }

    private fun exposed(
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
        category: SignalCategory,
    ): Boolean = !snapshot[category].isNullOrEmpty()

    /** First integer found among a category's signal values, if any. */
    private fun countFrom(
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
        category: SignalCategory,
    ): Int? {
        val signals = snapshot[category] ?: return null
        for (signal in signals) {
            val n = signal.value.filter { it.isDigit() }.take(9)
            if (n.isNotEmpty()) return n.toIntOrNull()
            signal.entries?.forEach { entry ->
                val e = entry.value.filter { it.isDigit() }.take(9)
                if (e.isNotEmpty()) return e.toIntOrNull()
            }
        }
        return null
    }

    private fun clipboardHasContent(
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
    ): Boolean {
        val signals = snapshot[SignalCategory.CLIPBOARD] ?: return false
        return signals.any { signal ->
            val v = signal.value.lowercase()
            v.contains("true") || v.contains("text") || v.contains("uri") || v.contains("html")
        }
    }

    /** Whether a category's exposure warrants a WARNING vs INFO for new-exposure alerts. */
    fun defaultLevelFor(category: SignalCategory): AlertLevel = when (category.sensitivity) {
        Sensitivity.PERMISSIONED -> AlertLevel.WARNING
        Sensitivity.ADVANCED -> AlertLevel.SUGGESTION
        Sensitivity.PASSIVE -> AlertLevel.INFO
    }

}
