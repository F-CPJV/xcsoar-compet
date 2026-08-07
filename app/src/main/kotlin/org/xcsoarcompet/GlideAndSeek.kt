package org.xcsoarcompet

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Repli pour les coordonnées des points de virage.
 *
 * Cinq compétitions sur les quatorze du relevé ne publient aucun fichier de
 * points de virage — sans lui, les noms lus sur la page « task » ne peuvent pas
 * être résolus en coordonnées. GlideAndSeek expose une API publique qui rend
 * directement les coordonnées de la task.
 *
 * On ne lui prend **que** les coordonnées : son API ne dit ni le type de
 * circuit, ni la durée AAT, ni les règles du jour, que nous lisons nous-mêmes
 * sur la page SoaringSpot.
 *
 * L'API n'accepte que la forme « résultats » de l'URL ; la forme « tasks »
 * renvoie 404.
 */
object GlideAndSeek {

    private const val API = "https://api.glideandseek.com/v2/task"

    fun taskUrl(slug: String, task: SoaringSpot.TaskRef): String =
        "${SoaringSpot.BASE}/en_gb/$slug/results/${task.cls}/" +
            "task-${task.number}-on-${task.date}/daily"

    /** Points de virage de la task, indexés par nom. */
    fun waypoints(slug: String, task: SoaringSpot.TaskRef): Map<String, Cup.Point> {
        val url = API + "?url=" + URLEncoder.encode(taskUrl(slug, task), "UTF-8")
        return parse(SoaringSpot.fetchText(url))
    }

    fun parse(json: String): Map<String, Cup.Point> {
        val root = JSONObject(json)
        if (!root.optBoolean("success", false))
            throw IllegalStateException(
                "GlideAndSeek: " + root.opt("message")?.toString().orEmpty()
            )
        val points = root.getJSONObject("message").optJSONArray("points")
            ?: return emptyMap()
        val out = LinkedHashMap<String, Cup.Point>()
        for (i in 0 until points.length()) {
            val p = points.getJSONObject(i)
            val name = p.optString("name").trim()
            if (name.isEmpty()) continue
            out[name] = Cup.Point(
                name = name,
                lat = p.getDouble("lat"),
                lon = p.getDouble("lng"),
                altitude = p.optDouble("altitude", 0.0).let { if (it.isNaN()) 0.0 else it },
            )
        }
        return out
    }
}
