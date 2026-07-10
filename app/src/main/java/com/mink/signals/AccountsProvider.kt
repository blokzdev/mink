package com.mink.signals

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * The account types registered on this phone, read through [AccountManager].
 * Mink reads only the type strings, for example com.google or com.whatsapp, and
 * how many of each. It never reads a username or an email. The set of services
 * you sign into is itself identifying: it maps out which providers you use.
 *
 * The category carries no permission in the model, so this provider checks
 * GET_ACCOUNTS itself and degrades to a single explanation when it is missing.
 */
class AccountsProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.ACCOUNTS

    override suspend fun collect(): List<FingerprintSignal> {
        if (!ctx.hasPermission(Manifest.permission.GET_ACCOUNTS)) {
            return listOf(
                FingerprintSignal.make(
                    key = "gate",
                    category = category,
                    name = "Permission needed",
                    value = "Gate closed",
                    rationale =
                        "Mink reads nothing here until you grant accounts access. The set of " +
                            "account types on your phone reveals which services you use, so it " +
                            "sits behind the GET_ACCOUNTS permission.",
                ),
            )
        }

        val accounts = readAccounts()
        if (accounts.isEmpty()) {
            return listOf(
                FingerprintSignal.make(
                    key = "none",
                    category = category,
                    name = "Registered accounts",
                    value = "None visible",
                    rationale =
                        "No account types were readable. Newer Android versions can hide " +
                            "accounts an app is not associated with.",
                ),
            )
        }

        val perType = accounts.groupingBy { it.type }.eachCount()
        val ordered = perType.entries.sortedByDescending { it.value }
        val signals = mutableListOf<FingerprintSignal>()

        signals += FingerprintSignal.make(
            key = "total",
            category = category,
            name = "Registered accounts",
            value = accounts.size.toString(),
            rationale =
                "How many accounts are registered on this phone across all services. Read " +
                    "from AccountManager.",
        )

        signals += FingerprintSignal.make(
            key = "distinctTypes",
            category = category,
            name = "Distinct account types",
            value = perType.size.toString(),
            rationale = "How many different services you sign into on this phone.",
        )

        val entries = ordered.map { SignalEntry(it.key, it.value.toString()) }
        signals += FingerprintSignal.make(
            key = "types",
            category = category,
            name = "Account types",
            value = ordered.joinToString(", ") { "${it.key} (${it.value})" },
            rationale =
                "The account type strings and how many of each. The mix names the providers " +
                    "you use, from mail and social to banking, without reading a single username.",
            displayHint = DisplayHint.TAGS,
            entries = entries,
        )

        return signals
    }

    private fun readAccounts(): List<Account> = runCatching {
        AccountManager.get(ctx.appContext).accounts.toList()
    }.getOrDefault(emptyList())
}
