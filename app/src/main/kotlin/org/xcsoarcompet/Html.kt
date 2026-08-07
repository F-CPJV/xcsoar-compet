package org.xcsoarcompet

/**
 * Décodage minimal d'entités HTML et retrait des balises.
 *
 * SoaringSpot sert de l'UTF-8 : seules les entités nommées courantes et les
 * références numériques sont à traiter. On évite android.text.Html, qui
 * reformate le texte (sauts de ligne, espaces) de façon peu prévisible.
 */
object Html {

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "deg" to "°", "hellip" to "…", "mdash" to "—",
        "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“",
        "rdquo" to "”", "times" to "×", "divide" to "÷", "plusmn" to "±",
        "micro" to "µ", "sup2" to "²", "sup3" to "³", "eacute" to "é",
        "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "agrave" to "à",
        "acirc" to "â", "ccedil" to "ç", "ugrave" to "ù", "ucirc" to "û",
        "uuml" to "ü", "ocirc" to "ô", "ouml" to "ö", "icirc" to "î",
        "iuml" to "ï", "auml" to "ä", "szlig" to "ß",
    )

    private val ENTITY = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);")
    private val TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)

    fun unescape(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") ->
                e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") ->
                e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }

    /** Retire les balises et décode les entités ; l'espace insécable devient une espace. */
    fun text(s: String): String =
        unescape(TAG.replace(s, "")).replace(' ', ' ').trim()

    /** Balises remplacées par une espace, blancs réduits : pour chercher une
     *  expression qui traverse plusieurs éléments. */
    fun flat(s: String): String =
        unescape(TAG.replace(s, " ")).replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    /** Idem, mais chaque balise devient un saut de ligne (pour les blocs de notes). */
    fun lines(s: String): String =
        unescape(TAG.replace(s, "\n")).replace(' ', ' ')
}
