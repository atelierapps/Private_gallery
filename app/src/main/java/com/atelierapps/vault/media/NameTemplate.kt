package com.atelierapps.vault.media

import com.atelierapps.vault.data.entity.MediaWithTags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renaming a batch by pattern rather than by one name.
 *
 * Giving forty files the same name is not renaming, it is losing them, so the
 * unit here is a template with tokens rather than a string. The user fills in
 * the parts that mean something to them and the app supplies the parts that
 * differ per item.
 *
 * Pure on purpose: the dialog's live preview and the actual rename both call
 * [expand], so what you are shown cannot drift from what you get.
 */
object NameTemplate {

    /** What a token stands for, and the label the picker shows for it. */
    enum class Token(val marker: String, val label: String, val hint: String) {
        NAME("{name}", "Original", "the current name, without its extension"),
        SOURCE("{source}", "Source", "where it came from — app or site"),
        NUMBER("{n}", "Number", "1, 2, 3… padded to the size of the batch"),
        DATE("{date}", "Date", "the item's date, in the format chosen below"),
    }

    /** Date formats offered for [Token.DATE]; the first is the default. */
    val DATE_FORMATS = listOf(
        "yyyy-MM-dd",
        "yyyyMMdd",
        "d MMM yyyy",
        "yyyy-MM",
        "yyyy-MM-dd HH-mm",
    )

    /**
     * Expands [template] for one item.
     *
     * @param index zero-based position in the batch
     * @param total batch size, which sets the zero-padding width so a run of a
     *   hundred sorts as 001…100 rather than 1, 10, 100
     *
     * The extension is never part of the template and is always re-appended
     * from the original, because a template that can strip ".mp4" is a template
     * that can quietly make files unopenable.
     */
    fun expand(
        template: String,
        item: MediaWithTags,
        index: Int,
        total: Int,
        dateFormat: String = DATE_FORMATS.first(),
    ): String {
        val original = item.media.originalName
        val stem = original.substringBeforeLast('.', original)
        val extension = original.substringAfterLast('.', "")

        val width = total.coerceAtLeast(1).toString().length
        val number = (index + 1).toString().padStart(width, '0')
        val source = item.media.sourceLabel
            ?: item.media.sourceDomain
            ?: item.media.sourcePackage?.substringAfterLast('.')
            ?: "Unknown"
        val date = runCatching {
            SimpleDateFormat(dateFormat, Locale.getDefault()).format(Date(item.media.dateTakenMillis))
        }.getOrDefault("")

        val body = template
            .replace(Token.NAME.marker, stem)
            .replace(Token.SOURCE.marker, source)
            .replace(Token.NUMBER.marker, number)
            .replace(Token.DATE.marker, date)
            .let(::sanitise)
            .ifBlank { stem }

        return if (extension.isEmpty()) body else "$body.$extension"
    }

    /**
     * Strips what a filename can't hold. Kept permissive — this is a display
     * name in an encrypted row, not a path — but a slash would read as a
     * directory the moment anything exported it.
     */
    private fun sanitise(name: String): String =
        name.filterNot { it in ILLEGAL || it.isISOControl() }
            .trim()
            .take(120)

    /** Characters a filename cannot hold. Spaces and hyphens are fine and stay. */
    private const val ILLEGAL = "/\\:*?\"<>|"

    /** True if the template would give every item the same name. */
    fun isAmbiguous(template: String): Boolean =
        !template.contains(Token.NUMBER.marker) && !template.contains(Token.NAME.marker)
}
