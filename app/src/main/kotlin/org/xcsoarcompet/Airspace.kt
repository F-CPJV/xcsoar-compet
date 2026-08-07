package org.xcsoarcompet

/**
 * Retrait des espaces aériens désactivés du jour dans un fichier OpenAir.
 *
 * Un fichier OpenAir est une suite de blocs commençant par « AC » ; le nom se
 * trouve sur la ligne « AN » qui suit.
 *
 * SoaringSpot publie les espaces inactifs sous forme d'une seule chaîne où les
 * noms sont joints par « , », **sans échappement** — et certains noms
 * contiennent eux-mêmes des virgules (relevé en Roumanie :
 * « LRR206 - Active H24, except SAR, Police, Medevac »). Découper cette liste
 * est donc impossible de façon fiable.
 *
 * On procède donc à l'envers : chaque nom lu dans le fichier openAir est
 * cherché dans la liste, encadré de virgules. Cela résout l'ambiguïté et évite
 * du même coup qu'un nom court n'en morde un plus long — « TMA LYON 3 » ne doit
 * pas retirer « TMA LYON 3 DES R3201B ».
 */
object Airspace {

    data class Result(
        val content: String,
        val removed: Map<String, Int>,
        /** Fragments de la liste qui ne correspondent à aucun espace du fichier. */
        val notFound: List<String>,
    )

    /**
     * Espaces réduits, casse ignorée, et espaces retirés autour des virgules —
     * appliqué des deux côtés pour que « A, B » et « A,B » se comparent.
     */
    private fun normalise(s: String) = s
        .replace(Regex("\\s+"), " ").trim().uppercase()
        .replace(Regex(" *, *"), ",")

    fun filter(openAir: String, inactiveRaw: String, header: String): Result {
        val listed = normalise(inactiveRaw)
        if (listed.isEmpty()) return Result(header + openAir, emptyMap(), emptyList())
        val haystack = ",$listed,"

        val lines = openAir.split("\n")
        val starts = lines.indices.filter { lines[it].startsWith("AC ") } + lines.size
        if (starts.size <= 1) {
            // pas un fichier openAir reconnaissable : on n'y touche pas
            return Result(header + openAir, emptyMap(), listOf(listed))
        }

        val out = StringBuilder(header)
        val removed = LinkedHashMap<String, Int>()
        for (i in 0 until starts.first()) out.append(lines[i]).append("\n")

        for (b in 0 until starts.size - 1) {
            val block = lines.subList(starts[b], starts[b + 1])
            val name = block.firstOrNull { it.startsWith("AN ") }
                ?.let { normalise(it.substring(3)) }
            if (name != null && name.isNotEmpty() && haystack.contains(",$name,")) {
                removed[name] = (removed[name] ?: 0) + 1
                continue
            }
            for (l in block) out.append(l).append("\n")
        }

        // ce qui reste de la liste une fois les noms reconnus retirés
        var rest = haystack
        for (name in removed.keys) rest = rest.replace(",$name,", ",")
        val notFound = rest.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        return Result(out.toString(), removed, notFound)
    }
}
