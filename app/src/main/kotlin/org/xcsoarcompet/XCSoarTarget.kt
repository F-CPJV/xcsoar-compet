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
    const val STORAGE_HELP_URL = "https://github.com/pjv/xcsoar-compet/wiki/Stockage"

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
     * Fait pointer le profil sur le fichier d'espaces aériens du jour.
     * XCSoar 7.44 utilise `AirspaceFileList` ; les versions antérieures
     * `AirspaceFile`. Retourne le message à afficher.
     */
    fun setAirspaceFile(target: Target, fileName: String): String {
        val prf = target.profileFile
        if (!prf.isFile) return "profil absent : sélectionnez « $fileName » dans XCSoar"
        val text = prf.readText(Charsets.UTF_8)
        for (key in listOf("AirspaceFileList", "AirspaceFile")) {
            val re = Regex("^($key=\")[^\"]*(\")", RegexOption.MULTILINE)
            if (re.containsMatchIn(text)) {
                val updated = re.replace(text) { m ->
                    m.groupValues[1] + "%LOCAL_PATH%\\" + fileName + m.groupValues[2]
                }
                if (updated == text) return "profil : déjà sur $fileName"
                prf.writeText(updated, Charsets.UTF_8)
                return "profil : $key → $fileName"
            }
        }
        return "clé AirspaceFile absente du profil : sélectionnez « $fileName » dans XCSoar"
    }
}
