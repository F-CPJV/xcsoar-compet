package org.xcsoarcompet

/**
 * Retrait des espaces aériens désactivés du jour dans un fichier OpenAir.
 *
 * Un fichier OpenAir est une suite de blocs commençant par « AC » ; le nom se
 * trouve sur la ligne « AN » qui suit. La comparaison est faite sur le nom
 * complet, espaces normalisés et casse ignorée : les suffixes d'activation
 * (« (MON-FRI) », « (MON-FRI HX) ») font partie du nom et doivent donc
 * correspondre exactement, ce qui évite de retirer un espace voisin.
 */
object Airspace {

    data class Result(
        val content: String,
        val removed: Map<String, Int>,
        val notFound: List<String>,
    )

    private fun normalise(s: String) = s.replace(Regex("\\s+"), " ").trim().uppercase()

    fun filter(openAir: String, inactiveNames: List<String>, header: String): Result {
        val targets = inactiveNames.map { normalise(it) }.toSet()
        val lines = openAir.split("\n")
        val starts = lines.indices.filter { lines[it].startsWith("AC ") } + lines.size
        val out = StringBuilder(header)
        val removed = LinkedHashMap<String, Int>()

        if (starts.size > 1) {
            for (i in 0 until starts.first()) out.append(lines[i]).append("\n")
        } else {
            // pas de bloc AC reconnu : on ne touche à rien
            return Result(header + openAir, emptyMap(), inactiveNames.map { normalise(it) })
        }

        for (b in 0 until starts.size - 1) {
            val from = starts[b]
            val to = starts[b + 1]
            val block = lines.subList(from, to)
            val name = block.firstOrNull { it.startsWith("AN ") }
                ?.let { normalise(it.substring(3)) }
            if (name != null && name in targets) {
                removed[name] = (removed[name] ?: 0) + 1
                continue
            }
            for (l in block) out.append(l).append("\n")
        }

        val notFound = targets.filter { it !in removed.keys }
        return Result(out.toString(), removed, notFound)
    }
}
