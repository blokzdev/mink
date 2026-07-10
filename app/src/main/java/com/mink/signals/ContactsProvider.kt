package com.mink.signals

import android.content.ContentResolver
import android.database.Cursor
import android.provider.ContactsContract
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Address book metadata, read through [ContactsContract]. Mink counts contacts,
 * phone and email fields, the accounts that sync them, and how you label
 * numbers. It never reads a name, number, or address; only the shape of your
 * social graph, which still hints at your circle.
 */
class ContactsProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.CONTACTS

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }

        val resolver = ctx.appContext.contentResolver
        val signals = mutableListOf<FingerprintSignal>()

        val total = count(resolver, ContactsContract.Contacts.CONTENT_URI)
        signals += FingerprintSignal.make(
            key = "total",
            category = category,
            name = "Contact count",
            value = total.toString(),
            rationale =
                "The size of your address book. The number alone hints at how socially or " +
                    "professionally connected you are.",
        )

        val withPhone = count(
            resolver,
            ContactsContract.Contacts.CONTENT_URI,
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
        )
        signals += FingerprintSignal.make(
            key = "withPhone",
            category = category,
            name = "Contacts with a phone number",
            value = withPhone.toString(),
            rationale = "How many of your contacts carry at least one phone number.",
        )

        val emailRows = count(resolver, ContactsContract.CommonDataKinds.Email.CONTENT_URI)
        signals += FingerprintSignal.make(
            key = "emailRows",
            category = category,
            name = "Email addresses stored",
            value = emailRows.toString(),
            rationale = "How many email addresses live across your contacts.",
        )

        val accountTypes = accountTypes(resolver)
        if (accountTypes.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "accounts",
                category = category,
                name = "Syncing accounts",
                value = accountTypes.joinToString(", "),
                rationale =
                    "The account types that sync contacts to this phone. The set of services " +
                        "you use is itself identifying.",
                displayHint = DisplayHint.TAGS,
                entries = accountTypes.map { SignalEntry(it, "") },
            )
        }

        val labels = phoneLabelHistogram(resolver)
        if (labels.isNotEmpty()) {
            val entries = labels.entries
                .sortedByDescending { it.value }
                .map { SignalEntry(it.key, it.value.toString()) }
            signals += FingerprintSignal.make(
                key = "labels",
                category = category,
                name = "Phone label histogram",
                value = entries.joinToString("\n") { "${it.label}: ${it.value}" },
                rationale =
                    "How you label numbers, for example home, work, or mobile. The mix reflects " +
                        "the kind of relationships you keep, with no names read.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = entries,
            )
        }

        return signals
    }

    private fun count(
        resolver: ContentResolver,
        uri: android.net.Uri,
        selection: String? = null,
    ): Int = runCatching {
        resolver.query(uri, arrayOf("_id"), selection, null, null)?.use { it.count } ?: 0
    }.getOrDefault(0)

    private fun accountTypes(resolver: ContentResolver): List<String> = runCatching {
        val types = sortedSetOf<String>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val col = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (cursor.moveToNext()) {
                if (col >= 0) cursor.getString(col)?.takeIf { it.isNotBlank() }?.let { types += it }
            }
        }
        types.toList()
    }.getOrDefault(emptyList())

    private fun phoneLabelHistogram(resolver: ContentResolver): Map<String, Int> = runCatching {
        val counts = linkedMapOf<String, Int>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.TYPE),
            null,
            null,
            null,
        )?.use { cursor -> accumulateLabels(cursor, counts) }
        counts
    }.getOrDefault(emptyMap())

    private fun accumulateLabels(cursor: Cursor, counts: MutableMap<String, Int>) {
        val typeCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
        while (cursor.moveToNext()) {
            val type = if (typeCol >= 0) cursor.getInt(typeCol) else -1
            val label = phoneLabel(type)
            counts[label] = (counts[label] ?: 0) + 1
        }
    }

    private fun phoneLabel(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "home fax"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "work fax"
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "pager"
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "other"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> "custom"
        else -> "unlabeled"
    }
}
