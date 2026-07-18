package com.mink.core.model

/**
 * Classification of how invasive a given fingerprinting signal is. Mirrors
 * Loupe's three-tier model: how much it costs an app to read the value.
 */
enum class Sensitivity {
    /** Any app can read these with no prompt at all. */
    PASSIVE,

    /** Reading these triggers an Android runtime permission prompt. */
    PERMISSIONED,

    /** Clever side-channel uses of public APIs to extract more than intended. */
    ADVANCED;

    val title: String
        get() = when (this) {
            PASSIVE -> "Passive"
            PERMISSIONED -> "Needs Permission"
            ADVANCED -> "Advanced"
        }

    val shortTitle: String
        get() = when (this) {
            PASSIVE -> "Passive"
            PERMISSIONED -> "Gated"
            ADVANCED -> "Advanced"
        }

    val blurb: String
        get() = when (this) {
            PASSIVE ->
                "Any app on your phone can read these. There's no prompt and nothing " +
                    "for you to see or approve."
            PERMISSIONED ->
                "Android shows a prompt the first time an app asks."
            ADVANCED ->
                "Clever uses of public APIs to extract more details than they were meant to."
        }

    /** Material symbol name resolved to a vector by the UI layer. */
    val iconKey: String
        get() = when (this) {
            PASSIVE -> "visibility"
            PERMISSIONED -> "shield_lock"
            ADVANCED -> "science"
        }
}
