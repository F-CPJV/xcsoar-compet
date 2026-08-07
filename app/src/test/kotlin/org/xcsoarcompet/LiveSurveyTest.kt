package org.xcsoarcompet

import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Passe le parseur sur de vraies compétitions, françaises et étrangères.
 *
 * Ne tourne pas en intégration continue : c'est un relevé, pas un test de
 * régression — SoaringSpot change tous les jours et le réseau est requis.
 *
 *   LIVE_SURVEY=1 ./gradlew testDebugUnitTest --tests '*LiveSurveyTest*'
 *
 * Le rapport est écrit dans build/live-survey.md.
 */
class LiveSurveyTest {

    private val competitions = listOf(
        // France
        "cdf2026-villefranche",                                   // championnat de France club
        "cdf2026-chalons",                                        // à venir
        "coulommiers-express-2026-coulommiers-voisins-2026",
        "les-8-jours-de-fontainebleau-2026-moret-episy-2026",
        "planeur-academie-fontenay-le-comte-2026",
        "pagn2026",
        // Allemagne
        "57-klippeneck-segelflug-wettbewerb-klippeneck-2026",
        "lahn-dill-bergland-cup-2026",
        // Royaume-Uni
        "cambridge-cloud-rally-2026-gransden-lodge-2026",
        "jonker-sailplanes-uk-club-nationals-2026",
        // Pologne
        "24th-fai-egc",                                           // championnats d'Europe FAI
        "lisie26",
        "lcup2026",
        "2026open",
        // Benelux
        "kiewit-cup-2026-kiewit-hasselt-2026",                    // Belgique
        "vergulde-venturi-20-2026-maldens-vlak-2026",             // Pays-Bas
        // Europe centrale et de l'Est
        "trogar-2-2026-nitra-2026",                               // Slovaquie
        "tauerncup2026",                                          // Autriche
        "25-hop-2026-velke-porici-2026",                          // Tchéquie, à venir
        "66th-ro-national-championship-2026-clubdouble-craiova-2026", // Roumanie, .cup vide
        "2026-m-domo-zostauto-taure-paluknys-2026",               // Lituanie
        "cempionat-mo-2026-klass-smesannyj-reshety",              // Russie
        // Sud de l'Europe
        "ccr-coppa-citta-di-rieti-rieti-2026",                    // Italie, à venir
        "grand-prix-club-la-cerdanya-2026-la-cerdanya-2026",      // Espagne, à venir
        // Hors d'Europe
        "2026-canadian-national-gliding-competition-rockton-2026", // Canada
        "wcr2026",                                                // Afrique du Sud
        "cempionat-rossii-2026-klass-otkrytyj-evsino",            // Australie
        "copa-oeste-2025-raul-o-bavaud-trenque-lauquen-2025",     // Argentine
        // International
        "jwgc2026",                                               // mondial junior
    )

    private val report = StringBuilder()

    private fun line(s: String) {
        println(s)
        report.append(s).append('\n')
    }

    @Test
    fun survey() {
        Assume.assumeTrue("relevé désactivé", System.getenv("LIVE_SURVEY") != null)

        var tasks = 0
        var built = 0
        var aat = 0
        val noWaypointFile = ArrayList<String>()
        val noAirspaceFile = ArrayList<String>()
        val unknownZones = LinkedHashMap<String, Int>()
        val missingPoints = ArrayList<String>()
        val noRules = ArrayList<String>()
        val airspaceMisses = ArrayList<String>()
        val gasFailures = ArrayList<String>()
        var gasUsed = 0
        var upcoming = 0
        val allZones = LinkedHashMap<String, Pair<Int, String>>()

        line("| compétition | classe | task | type | pts | circuit | règles | espaces inactifs |")
        line("|---|---|---|---|---|---|---|---|")

        for (slug in competitions) {
            val refs = try {
                SoaringSpot.listTasks(slug)
            } catch (e: Exception) {
                line("| $slug | — | — | — | — | ❌ ${e.message} | | |")
                continue
            }
            if (refs.isEmpty()) {
                val dates = try { SoaringSpot.competitionDates(slug) } catch (e: Exception) { null }
                val when_ = dates?.let { "épreuve à venir (${it.first} → ${it.second})" }
                    ?: "aucune task publiée"
                line("| $slug | — | — | — | — | $when_ | | |")
                upcoming++
                continue
            }

            val files = try { SoaringSpot.listContestFiles(slug) } catch (e: Exception) { emptyList() }
            val wpFile = SoaringSpot.waypointFile(files)
            val asFile = SoaringSpot.airspaceFile(files)
            if (wpFile == null) noWaypointFile.add(slug)
            if (asFile == null) noAirspaceFile.add(slug)

            val waypoints = wpFile?.let {
                try { Cup.parse(String(SoaringSpot.download(it), Charsets.UTF_8)) }
                catch (e: Exception) { null }
            }
            var openAir: String? = null

            // dernière task de chaque classe
            for (ref in refs.groupBy { it.cls }.map { it.value.last() }) {
                tasks++
                val parsed = try {
                    SoaringSpot.parseTask(SoaringSpot.fetchText(SoaringSpot.taskUrl(slug, ref)))
                } catch (e: Exception) {
                    line("| $slug | ${ref.cls} | ${ref.number} | — | — | ❌ ${e.message} | | |")
                    continue
                }
                if (parsed.isAat) aat++

                parsed.points.filter { !TaskXml.isKnownZone(it.zone) }.forEach {
                    unknownZones[it.zone] = (unknownZones[it.zone] ?: 0) + 1
                }
                parsed.points.forEach { tp ->
                    val key = tp.zone + "  →  " + TaskXml.observationZone(tp.zone)
                    val seen = allZones[key]
                    allZones[key] = ((seen?.first ?: 0) + 1) to
                        (seen?.second ?: "$slug/${ref.cls} task ${ref.number}, ${tp.name}")
                }

                val rules = TaskXml.parseRules(parsed.notes)
                val rulesFound = listOfNotNull(
                    rules.startMaxSpeed?.let { "V" },
                    rules.finishMinHeight?.let { "H" },
                ).joinToString("").ifEmpty { "—" }
                if (rulesFound == "—") noRules.add("$slug/${ref.cls}")

                var taskState: String
                var points = waypoints ?: emptyMap()
                var missing = parsed.points.map { it.name }.filter { !points.containsKey(it) }
                var viaGas = false
                if (missing.isNotEmpty()) {
                    // même repli que l'appli
                    try {
                        val gas = GlideAndSeek.waypoints(slug, ref)
                        if (gas.isNotEmpty()) {
                            points = gas + points
                            viaGas = true
                            missing = parsed.points.map { it.name }.filter { !points.containsKey(it) }
                        }
                    } catch (e: Exception) {
                        gasFailures.add("$slug/${ref.cls}: ${e.message}")
                    }
                }
                if (missing.isEmpty() && points.isNotEmpty()) {
                    TaskXml.build(parsed, points, rules)
                    built++
                    taskState = if (viaGas) "✔ (GlideAndSeek)" else "✔"
                    if (viaGas) gasUsed++
                } else {
                    taskState = "❌ ${missing.size} pt(s) absents"
                    missingPoints.add("$slug/${ref.cls}: ${missing.take(3)}")
                }

                var asState = "—"
                if (parsed.inactiveAirspaces.isNotEmpty()) {
                    if (asFile == null) {
                        asState = "${parsed.inactiveAirspaces.size} listés, pas de fichier"
                    } else {
                        if (openAir == null)
                            openAir = String(SoaringSpot.download(asFile), Charsets.UTF_8)
                        val res = Airspace.filter(openAir!!, parsed.inactiveRaw, "")
                        asState = "${res.removed.size}/${parsed.inactiveAirspaces.size}"
                        if (res.notFound.isNotEmpty())
                            airspaceMisses.add("$slug/${ref.cls}: ${res.notFound.take(3)}")
                    }
                }

                val kind = if (parsed.isAat) "AAT ${parsed.aatSeconds!! / 60}min" else "RT"
                line(
                    "| $slug | ${ref.cls} | ${ref.number} | $kind | ${parsed.points.size} " +
                        "| $taskState | $rulesFound | $asState |"
                )
            }
        }

        line("")
        line("## Synthèse")
        line("- compétitions non encore commencées (donc sans task) : $upcoming")
        line("- tasks analysées : $tasks, circuits générés : $built, dont AAT : $aat")
        line("- circuits résolus grâce au repli GlideAndSeek : $gasUsed ; échecs du repli : ${gasFailures.size} ${gasFailures.take(4)}")
        line("- compétitions sans fichier de points de virage : ${noWaypointFile.size} ${noWaypointFile}")
        line("- compétitions sans fichier d'espaces : ${noAirspaceFile.size} ${noAirspaceFile}")
        line("- règles du jour non trouvées : ${noRules.size} ${noRules.take(8)}")
        line("- points de circuit absents du .cup : ${missingPoints.size}")
        missingPoints.take(8).forEach { line("  - $it") }
        line("- espaces inactifs introuvables : ${airspaceMisses.size}")
        airspaceMisses.take(8).forEach { line("  - $it") }
        line("")
        line("## Zones d'observation rencontrées")
        allZones.entries.sortedByDescending { it.value.first }.forEach {
            line("- ${it.value.first}× `${it.key}`")
            line("  <br>ex. ${it.value.second}")
        }
        line("")
        line("- zones d'observation non reconnues : ${unknownZones.size}")
        unknownZones.entries.take(10).forEach { line("  - ${it.value}× « ${it.key} »") }

        File("build/live-survey.md").writeText(report.toString())
    }
}
