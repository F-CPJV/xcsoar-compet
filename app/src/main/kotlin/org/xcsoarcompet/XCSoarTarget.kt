package org.xcsoarcompet

import java.io.File

/**
 * Répertoires de données XCSoar accessibles depuis une autre application.
 *
 * Depuis la 7.43, XCSoar range ses données dans `Android/media/<paquet>/`,
 * qui est du stockage partagé — accessible avec MANAGE_EXTERNAL_STORAGE.
 * Mais une installation mise à jour depuis une version antérieure reste
 * accrochée à `Android/data/<paquet>/files/`, inaccessible à toute autre
 * application : XCSoar continue alors d'utiliser ce répertoire privé tant
 * qu'il y trouve un `xcsoar.log`, et n'ajoute même pas `Android/media` à
 * ses chemins de données. Ce que nous écrivons serait alors ignoré, sans
 * aucun message : d'où la vérification `hasEvidenceOfUse`.
 */
object XCSoarTarget {

    /** Page expliquant le cas « XCSoar ne lit pas Android/media ». */
    const val STORAGE_HELP_URL =
        "https://github.com/F-CPJV/xcsoar-compet/wiki/Stockage"

    private val MEDIA_ROOT = File("/storage/emulated/0/Android/media")

    data class Target(val dir: File) {
        val packageName: String get() = dir.name
        val logFile: File get() = File(dir, "xcsoar.log")
        val profileFile: File get() = File(dir, "default.prf")

        /**
         * XCSoar écrit son journal dans son répertoire de données actif. Sa
         * présence prouve donc que c'est bien ce répertoire-là qu'il utilise.
         */
        val hasEvidenceOfUse: Boolean get() = logFile.isFile

        override fun toString(): String = packageName
    }

    /** Sous-répertoires de Android/media dont le nom contient « soar ». */
    fun discover(): List<Target> {
        val dirs = MEDIA_ROOT.listFiles() ?: return emptyList()
        return dirs
            .filter { it.isDirectory && it.name.contains("soar", ignoreCase = true) }
            .sortedBy { it.name }
            .map { Target(it) }
    }

    fun write(target: Target, name: String, bytes: ByteArray) {
        val out = File(target.dir, name)
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
    }

    fun write(target: Target, name: String, text: String) =
        write(target, name, text.toByteArray(Charsets.UTF_8))

    /**
     * Déposer un fichier ne suffit pas : XCSoar ne charge que ce que le profil
     * désigne. Ces clés acceptent plusieurs fichiers séparés par « | »
     * (`ProfileMap::GetMultiplePaths`), chacun préfixé de `%LOCAL_PATH%`.
     *
     * Noms de clés : `AirspaceFileList` / `WPFileList` depuis la 7.43,
     * `AirspaceFile` / `WPFile` avant.
     */
    private const val LOCAL = "%LOCAL_PATH%\\"

    fun readValue(profile: String, keys: List<String>): Pair<String, String>? {
        for (key in keys) {
            val m = Regex("^$key=\"([^\"]*)\"", RegexOption.MULTILINE).find(profile)
            if (m != null) return key to m.groupValues[1]
        }
        return null
    }

    /** Écrit la clé si elle existe, l'ajoute en fin de profil sinon. */
    fun writeValue(profile: String, key: String, value: String): String {
        val re = Regex("^$key=\"[^\"]*\"", RegexOption.MULTILINE)
        // forme lambda : la valeur est insérée telle quelle, sans échappement
        if (re.containsMatchIn(profile))
            return re.replace(profile) { "$key=\"$value\"" }
        val separator = if (profile.isEmpty() || profile.endsWith("\n")) "" else "\n"
        return profile + separator + key + "=\"" + value + "\"\n"
    }

    /**
     * Espaces aériens et points de virage du jour dans le profil.
     *
     * Les deux **remplacent** la liste existante, ils ne s'y ajoutent pas.
     * Pour les espaces, garder le fichier national du pilote y réintroduirait
     * les zones que l'organisateur a désactivées. Pour les points de virage,
     * le fichier de l'organisateur fait référence pendant l'épreuve : y
     * superposer la base personnelle du pilote ferait apparaître des points en
     * double, aux coordonnées parfois légèrement différentes.
     *
     * La valeur précédente est rapportée pour que le pilote puisse la remettre
     * après la compétition.
     */
    fun setProfileFiles(target: Target, airspace: String?, waypoints: String?): List<String> {
        val prf = target.profileFile
        if (!prf.isFile) return listOf(
            "! no default.prf yet — start XCSoar once, then install again"
        )
        var text = prf.readText(Charsets.UTF_8)
        val messages = ArrayList<String>()

        fun setFile(what: String, keys: List<String>, fileName: String) {
            val found = readValue(text, keys)
            val key = found?.first ?: keys.first()
            val previous = found?.second.orEmpty()
            val wanted = LOCAL + fileName
            if (previous == wanted) {
                messages.add("profile: $what already set")
                return
            }
            if (previous.isNotBlank())
                messages.add("profile: previous $what was $previous")
            text = writeValue(text, key, wanted)
            messages.add("profile: $key -> $fileName")
        }

        if (airspace != null)
            setFile("airspace", listOf("AirspaceFileList", "AirspaceFile"), airspace)
        if (waypoints != null)
            setFile("waypoints", listOf("WPFileList", "WPFile"), waypoints)

        prf.writeText(text, Charsets.UTF_8)
        return messages
    }
}
