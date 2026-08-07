package org.xcsoarcompet

import java.util.Locale

/**
 * Génération du fichier de circuit XCSoar (.tsk).
 *
 * Correspondance des zones d'observation SoaringSpot → XCSoar :
 *   « Line 10.00 km »                                  → Line (length)
 *   secteur symétrique Rmin/Rmax/Angle + cylindre       → CustomKeyhole
 *   idem avec Angle=360°  (zone AAT circulaire)         → Cylinder (Rmax)
 *   « Cylinder R=x km »                                 → Cylinder
 */
object TaskXml {

    class MissingPointException(val names: List<String>) : Exception(
        "points absents du fichier de points de virage : " + names.joinToString(", ")
    )

    data class Rules(
        val startMaxSpeed: Double? = null,   // m/s
        val finishMinHeight: Int? = null,    // m MSL
        val startMaxHeight: Int? = null,     // m MSL
    )

    /** Règles chiffrées extraites des « task notes ». */
    fun parseRules(notes: String): Rules {
        val speed = Regex("""[Vv]itesse max.*?d[ée]part\D*?(\d{2,3})\s*km/h""")
            .find(notes)?.groupValues?.get(1)?.toIntOrNull()
        val finish = Regex("""[Aa]ltitude min.*?arriv[ée]e\D*?(\d{3,4})\s*m""")
            .find(notes)?.groupValues?.get(1)?.toIntOrNull()
        val start = Regex("""[Aa]ltitude max.*?d[ée]part\D*?(\d{3,4})\s*m""")
            .find(notes)?.groupValues?.get(1)?.toIntOrNull()
        return Rules(speed?.let { it / 3.6 }, finish, start)
    }

    private fun km(zone: String, pattern: String): Int? =
        Regex(pattern).find(zone)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { Math.round(it * 1000).toInt() }

    fun observationZone(zone: String): String {
        if (zone.startsWith("Line")) {
            val length = km(zone, """Line ([\d.]+) km""") ?: 10000
            return """<ObservationZone length="$length" type="Line"/>"""
        }
        if (zone.contains("Rmin=") && zone.contains("Rmax=")) {
            val rmax = km(zone, """Rmax=([\d.]+) km""") ?: 20000
            val rmin = km(zone, """Cylinder R=([\d.]+) km""")
                ?: km(zone, """Rmin=([\d.]+) km""") ?: 500
            val angle = Regex("""Angle=([\d.]+)""").find(zone)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 90.0
            if (angle >= 360.0)
                return """<ObservationZone radius="$rmax" type="Cylinder"/>"""
            val a = if (angle == Math.floor(angle)) angle.toInt().toString()
            else angle.toString()
            return """<ObservationZone angle="$a" radius="$rmax" """ +
                """inner_radius="$rmin" type="CustomKeyhole"/>"""
        }
        if (zone.startsWith("Cylinder")) {
            val r = km(zone, """R=([\d.]+) km""") ?: 500
            return """<ObservationZone radius="$r" type="Cylinder"/>"""
        }
        // Zone non reconnue. Cas relevé en compétition : le secteur « next »,
        // orienté sur la branche suivante, que XCSoar ne sait pas stocker tel
        // quel. On retient au moins le rayon annoncé — un cylindre de 500 m par
        // défaut serait très inférieur à la zone réelle. isKnownZone() reste
        // faux pour que l'appelant prévienne le pilote.
        val r = km(zone, """R(?:max)?=([\d.]+) km""") ?: 500
        return """<ObservationZone radius="$r" type="Cylinder"/>"""
    }

    fun isKnownZone(zone: String): Boolean =
        zone.startsWith("Line") || zone.startsWith("Cylinder") ||
            (zone.contains("Rmin=") && zone.contains("Rmax="))

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun build(
        task: SoaringSpot.Task,
        waypoints: Map<String, Cup.Point>,
        rules: Rules,
    ): String {
        val missing = task.points.map { it.name }.filter { !waypoints.containsKey(it) }
        if (missing.isNotEmpty()) throw MissingPointException(missing)

        val sb = StringBuilder()
        val speed = rules.startMaxSpeed ?: 0.0
        sb.append(
            "<Task pev_start_window=\"0\" pev_start_wait_time=\"0\" fai_finish=\"0\"" +
                " finish_min_height_ref=\"MSL\"" +
                " finish_min_height=\"${rules.finishMinHeight ?: 0}\"" +
                " start_max_height_ref=\"MSL\"" +
                " start_max_height=\"${rules.startMaxHeight ?: 0}\"" +
                " start_max_speed=\"" + String.format(Locale.US, "%.4f", speed) + "\"" +
                " start_score_exit=\"0\" start_requires_arm=\"0\"" +
                " aat_min_time=\"${task.aatSeconds ?: 0}\"" +
                " type=\"${if (task.isAat) "AAT" else "RT"}\">\n"
        )
        task.points.forEachIndexed { i, tp ->
            val kind = when (i) {
                0 -> "Start"
                task.points.size - 1 -> "Finish"
                else -> "Turn"
            }
            val wp = waypoints.getValue(tp.name)
            sb.append("\t<Point type=\"$kind\">\n")
            sb.append(
                "\t\t<Waypoint altitude=\"" +
                    String.format(Locale.US, "%.0f", wp.altitude) +
                    "\" comment=\"\" id=\"${i + 1}\" name=\"${escape(tp.name)}\">\n"
            )
            sb.append(
                "\t\t\t<Location latitude=\"" +
                    String.format(Locale.US, "%.6f", wp.lat) +
                    "\" longitude=\"" +
                    String.format(Locale.US, "%.6f", wp.lon) + "\"/>\n"
            )
            sb.append("\t\t</Waypoint>\n")
            sb.append("\t\t").append(observationZone(tp.zone)).append("\n")
            sb.append("\t</Point>\n")
        }
        sb.append("</Task>\n")
        return sb.toString()
    }
}
