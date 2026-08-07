package org.xcsoarcompet

import java.net.HttpURLConnection
import java.net.URL

/**
 * Lecture des pages SoaringSpot.
 *
 * SoaringSpot ne publie aucun fichier de circuit téléchargeable et son API
 * (api.soaringspot.com) exige une clé délivrée par l'organisateur, valable
 * pour sa seule compétition. Le circuit est donc reconstruit depuis la page
 * « task », dont on cible les classes CSS (task-duration, task-version,
 * task-excluded-airspaces, table.task) plutôt que le texte affiché.
 */
object SoaringSpot {

    const val BASE = "https://www.soaringspot.com"

    data class TaskRef(val cls: String, val number: Int, val date: String) {
        val label: String get() = "Task $number — $date"
    }

    data class Turnpoint(val name: String, val zone: String)

    data class Task(
        val version: String,
        val points: List<Turnpoint>,
        val inactiveAirspaces: List<String>,
        /** liste telle que publiée : les noms peuvent contenir des virgules */
        val inactiveRaw: String,
        val notes: String,
        val aatSeconds: Int?,
    ) {
        val isAat: Boolean get() = aatSeconds != null
    }

    data class ContestFile(val name: String, val href: String)

    data class Competition(val slug: String, val name: String, val info: String) {
        override fun toString(): String = if (info.isEmpty()) name else "$name — $info"
    }

    private val DOTALL = setOf(RegexOption.DOT_MATCHES_ALL)

    class HttpException(val code: Int, url: String) :
        java.io.IOException("HTTP $code sur $url")

    /**
     * Trois tentatives : au briefing, un aléa réseau ne doit pas laisser le
     * pilote sans circuit. On ne réessaie pas une erreur 4xx, qui ne changera
     * pas d'avis.
     */
    fun fetch(url: String): ByteArray {
        var last: java.io.IOException? = null
        for (attempt in 0 until 3) {
            try {
                return fetchOnce(url)
            } catch (e: HttpException) {
                if (e.code in 400..499) throw e
                last = e
            } catch (e: java.io.IOException) {
                last = e
            }
            Thread.sleep(1000L * (attempt + 1))
        }
        throw last!!
    }

    private fun fetchOnce(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "XCSoarCompet/0.1 (+github.com/xcsoar-compet)")
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299)
                throw HttpException(conn.responseCode, url)
            return conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    fun fetchText(url: String): String = String(fetch(url), Charsets.UTF_8)

    /** Extrait le slug d'une URL complète ou le renvoie tel quel. */
    fun slugOf(input: String): String {
        val cleaned = input.trim().trimEnd('/')
        if (!cleaned.contains("/")) return cleaned
        val parts = cleaned.split("/").filter { it.isNotEmpty() }
        // .../en_gb/<slug>/... : le segment suivant le code langue
        val i = parts.indexOfFirst { Regex("^[a-z]{2}(_[a-z]{2})?$").matches(it) }
        return if (i >= 0 && i + 1 < parts.size) parts[i + 1] else parts.last()
    }

    /**
     * Recherche de compétitions par nom, lieu ou année (moteur de SoaringSpot).
     * Une requête vide renvoie les compétitions en cours listées sur l'accueil.
     */
    fun search(query: String): List<Competition> {
        val q = query.trim()
        val url = if (q.isEmpty()) "$BASE/en_gb/"
        else "$BASE/en_gb/search/?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        return parseCompetitions(fetchText(url))
    }

    fun parseCompetitions(html: String): List<Competition> {
        val out = LinkedHashMap<String, Competition>()
        val block = Regex(
            "<div class=\"contest\">(.*?)(?=<div class=\"contest\">|</ul>)", DOTALL
        )
        for (m in block.findAll(html)) {
            val chunk = m.groupValues[1]
            val link = Regex("<a href=\"/en_gb/([^/\"]+)/?\">(.*?)</a>", DOTALL)
                .find(chunk) ?: continue
            val slug = link.groupValues[1]
            val name = Html.text(link.groupValues[2]).trim()
            if (name.isEmpty()) continue
            val info = Regex("<div class=\"info\">(.*?)</div>", DOTALL).find(chunk)
                ?.let { Html.text(it.groupValues[1]).replace(Regex("\\s+"), " ").trim(' ', ',') }
                ?: ""
            out.putIfAbsent(slug, Competition(slug, name, info))
        }
        return out.values.toList()
    }

    private val DATE_RANGE =
        Regex("""(\d{1,2} [A-Za-zéûà]+ \d{4}) *[\u2013-] *(\d{1,2} [A-Za-zéûà]+ \d{4})""")

    /**
     * Dates de la compétition, telles qu'affichées en tête de page. Utile pour
     * distinguer « aucune task » d'une épreuve simplement à venir.
     */
    fun competitionDates(slug: String): Pair<String, String>? {
        val flat = Html.flat(
            fetchText("$BASE/en_gb/$slug/").replace(Regex("<script.*?</script>", DOTALL), " ")
        )
        return DATE_RANGE.find(flat)?.let { it.groupValues[1] to it.groupValues[2] }
    }

    /** Toutes les tasks publiées, toutes classes confondues. */
    fun listTasks(slug: String): List<TaskRef> {
        val html = fetchText("$BASE/en_gb/$slug/results")
        val re = Regex(
            "/en_gb/" + Regex.escape(slug) +
                "/(?:results|tasks)/([a-z0-9_-]+)/task-(\\d+)-on-(\\d{4}-\\d{2}-\\d{2})"
        )
        return re.findAll(html)
            .map { TaskRef(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) }
            .distinct()
            .sortedWith(compareBy({ it.cls }, { it.number }))
            .toList()
    }

    /**
     * Charge et analyse la page d'une task, avec reprises.
     *
     * SoaringSpot a servi une fois, en HTTP 200, une page amputée de son
     * tableau de points de virage (relevé sur trogar-2-2026 / open / task 6,
     * non reproductible ensuite). Une page incomplète ne doit pas se solder par
     * un échec définitif.
     */
    fun loadTask(slug: String, ref: TaskRef, attempts: Int = 3): Task {
        var last: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                return parseTask(fetchText(taskUrl(slug, ref)))
            } catch (e: Exception) {
                last = e
                if (attempt < attempts - 1) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw last!!
    }

    fun taskUrl(slug: String, t: TaskRef) =
        "$BASE/en_gb/$slug/tasks/${t.cls}/task-${t.number}-on-${t.date}"

    fun parseTask(html: String): Task {
        val version = Regex("task-version.*?<strong>([^<]+)</strong>", DOTALL)
            .find(html)?.groupValues?.get(1)?.trim() ?: "?"

        val tbody = Regex("<table class=\"task[^\"]*\".*?<tbody>(.*?)</tbody>", DOTALL)
            .find(html) ?: throw IllegalStateException(
            "tableau des points de virage introuvable — la page SoaringSpot a changé ?"
        )

        val points = ArrayList<Turnpoint>()
        for (tr in Regex("<tr>(.*?)</tr>", DOTALL).findAll(tbody.groupValues[1])) {
            val tds = Regex("<td[^>]*>(.*?)</td>", DOTALL)
                .findAll(tr.groupValues[1])
                .map { Html.text(it.groupValues[1]) }
                .toList()
            if (tds.size >= 4) points.add(Turnpoint(tds[0], tds[3]))
        }
        if (points.size < 2)
            throw IllegalStateException("seulement ${points.size} point(s) trouvé(s)")

        val inactiveRaw = Regex("task-excluded-airspaces.*?</i>(.*?)</div>", DOTALL)
            .find(html)?.let { m ->
                Html.text(m.groupValues[1])
                    .replace(Regex("Inactive airspaces\\s*:?", RegexOption.IGNORE_CASE), "")
                    .trim()
            } ?: ""
        val inactive = inactiveRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val notes = Regex("Task notes(.*?)(?:<footer|task-excluded|$)", DOTALL)
            .find(html)?.let { Html.lines(it.groupValues[1]) } ?: ""

        // AAT : « Task duration: h:mm:ss » dans le bloc task-duration
        val aat = Regex(
            "task-duration.*?<strong>\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*</strong>", DOTALL
        ).find(html)?.let {
            it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 +
                (it.groupValues[3].toIntOrNull() ?: 0)
        }

        return Task(version, points, inactive, inactiveRaw, notes, aat)
    }

    /** Fichiers publiés par l'organisateur (onglet Downloads). */
    fun listContestFiles(slug: String): List<ContestFile> {
        val html = fetchText("$BASE/en_gb/$slug/downloads")
        return Regex("href=\"(/en_gb/download-contest-file/[^\"]+)\">(.*?)</a>", DOTALL)
            .findAll(html)
            .map { ContestFile(Html.text(it.groupValues[2]), it.groupValues[1]) }
            .filter { it.name.isNotEmpty() }
            .toList()
    }

    /**
     * Fichier de points de virage.
     *
     * Les variantes constructeur (LX, ILEC, Garmin, Volkslogger, WinPilot,
     * Zander) portent d'autres extensions : filtrer sur « .cup » suffit. Surtout
     * ne pas écarter les noms contenant un tiret bas — beaucoup d'organisateurs
     * en mettent (`LDBCM_20260705.cup`, `JWGC_TP_v2.cup`), et les exclure
     * faisait passer cinq compétitions sur quatorze pour dépourvues de fichier.
     * S'il y en a plusieurs, le dernier par ordre alphabétique retient en
     * général la version la plus récente (`_v1` < `_v2`).
     */
    fun waypointFile(files: List<ContestFile>): ContestFile? =
        files.filter { it.name.endsWith(".cup", true) }.maxByOrNull { it.name }

    /** Fichier d'espaces aériens OpenAir (.txt) ; le .cub est réservé aux Oudie. */
    fun airspaceFile(files: List<ContestFile>): ContestFile? =
        files.filter { it.name.endsWith(".txt", true) }.maxByOrNull { it.name }

    fun download(f: ContestFile): ByteArray = fetch(BASE + f.href)
}
