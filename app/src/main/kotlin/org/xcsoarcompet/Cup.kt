package org.xcsoarcompet

/**
 * Lecture des fichiers de points de virage au format SeeYou CUP.
 *
 * Le découpage respecte les guillemets : une virgule à l'intérieur d'un champ
 * cité (fréquent dans les descriptions openAIP) ne coupe pas la ligne.
 */
object Cup {

    data class Point(val name: String, val lat: Double, val lon: Double, val altitude: Double)

    private val COORD = Regex("""^(\d{2,3})(\d{2}\.\d+)([NSEW])$""")
    private val LEADING_NUMBER = Regex("""^([\d.]+)""")

    /** Découpe une ligne CSV en respectant les guillemets doubles (doublés pour échapper). */
    fun splitLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    /** « 4542.467N » → 45.70778 (négatif pour S et W). */
    fun parseCoord(field: String): Double? {
        val m = COORD.matchEntire(field.trim()) ?: return null
        val deg = m.groupValues[1].toInt()
        val min = m.groupValues[2].toDouble()
        val value = deg + min / 60.0
        return if (m.groupValues[3] == "S" || m.groupValues[3] == "W") -value else value
    }

    /** 45.70778 → « 4542.467N » (ou « 00201.950E » en longitude). */
    fun formatCoord(value: Double, isLatitude: Boolean): String {
        val hemisphere = if (isLatitude) (if (value < 0) 'S' else 'N')
        else (if (value < 0) 'W' else 'E')
        val abs = Math.abs(value)
        val degrees = abs.toInt()
        val minutes = (abs - degrees) * 60.0
        val width = if (isLatitude) 2 else 3
        return String.format(
            java.util.Locale.US, "%0${width}d%06.3f%c", degrees, minutes, hemisphere
        )
    }

    /**
     * Écrit un fichier CUP minimal. Sert quand les coordonnées viennent de
     * GlideAndSeek faute de fichier publié par l'organisateur : XCSoar dispose
     * ainsi quand même des points de virage de l'épreuve.
     */
    fun write(points: Collection<Point>): String {
        val sb = StringBuilder("name,code,country,lat,lon,elev,style,rwdir,rwlen,freq,desc\n")
        for (p in points) {
            sb.append('"').append(p.name.replace("\"", "\"\"")).append("\",\"\",,")
                .append(formatCoord(p.lat, true)).append(',')
                .append(formatCoord(p.lon, false)).append(',')
                .append(String.format(java.util.Locale.US, "%.0fm", p.altitude))
                .append(",1,,,,\"\"\n")
        }
        return sb.toString()
    }

    /** Indexe un fichier CUP par nom de point. */
    fun parse(content: String): Map<String, Point> {
        val points = LinkedHashMap<String, Point>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // le format autorise une section « tâches » après une ligne -----
            if (line.startsWith("-----")) break
            val f = splitLine(line)
            if (f.size < 6) continue
            if (f[0].equals("name", ignoreCase = true)) continue
            val lat = parseCoord(f[3]) ?: continue
            val lon = parseCoord(f[4]) ?: continue
            val alt = LEADING_NUMBER.find(f[5].trim())?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val name = f[0].trim()
            points[name] = Point(name, lat, lon, alt)
        }
        return points
    }
}
