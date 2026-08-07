package org.xcsoarcompet

/**
 * Récupération d'une compétition SoaringSpot et installation dans XCSoar.
 *
 * Fonctionne hors du thread d'interface ; chaque étape est rapportée via [log].
 */
class Installer(
    private val slug: String,
    private val task: SoaringSpot.TaskRef,
    private val target: XCSoarTarget.Target,
    private val options: Options,
    private val log: (String) -> Unit,
) {

    data class Options(
        val installTask: Boolean = true,
        val setAsDefaultTask: Boolean = true,
        val installWaypoints: Boolean = true,
        val installAirspace: Boolean = true,
        val updateProfile: Boolean = true,
    )

    companion object {
        const val TASK_FILE = "compet_task.tsk"
        const val AIRSPACE_FILE = "compet_airspace.txt"
        const val DEFAULT_TASK_FILE = "Default.tsk"
    }

    fun run(): Boolean {
        var ok = true
        var airspaceInstalled: String? = null
        var waypointsInstalled: String? = null
        val page = SoaringSpot.fetchText(SoaringSpot.taskUrl(slug, task))
        val parsed = SoaringSpot.parseTask(page)
        val kind = if (parsed.isAat) {
            val m = parsed.aatSeconds!! / 60
            "AAT ${m / 60}h${String.format("%02d", m % 60)}"
        } else "racing task"
        log("Task ${task.number} (${task.cls}) ${parsed.version}: ${parsed.points.size} points, $kind")

        parsed.points.filter { !TaskXml.isKnownZone(it.zone) }.forEach {
            log("! unknown observation zone on ${it.name}: \"${it.zone}\" — using a 500 m cylinder")
            ok = false
        }

        val files = SoaringSpot.listContestFiles(slug)
        val wpFile = SoaringSpot.waypointFile(files)
        val asFile = SoaringSpot.airspaceFile(files)

        if (options.installTask || options.installWaypoints) {
            if (wpFile == null) {
                log("! no waypoint file (.cup) published by the organiser")
                return false
            }
            val cupBytes = SoaringSpot.download(wpFile)
            if (options.installWaypoints) {
                XCSoarTarget.write(target, wpFile.name, cupBytes)
                log("waypoints: ${wpFile.name}")
                waypointsInstalled = wpFile.name
            }
            if (options.installTask) {
                val waypoints = Cup.parse(String(cupBytes, Charsets.UTF_8))
                val rules = TaskXml.parseRules(parsed.notes)
                rules.startMaxSpeed?.let { log("rule: max start speed ${Math.round(it * 3.6)} km/h") }
                rules.finishMinHeight?.let { log("rule: min finish height $it m") }
                val xml = try {
                    TaskXml.build(parsed, waypoints, rules)
                } catch (e: TaskXml.MissingPointException) {
                    log("! ${e.message}")
                    return false
                }
                XCSoarTarget.write(target, TASK_FILE, xml)
                log("task: $TASK_FILE")
                if (options.setAsDefaultTask) {
                    XCSoarTarget.write(target, DEFAULT_TASK_FILE, xml)
                    log("task: $DEFAULT_TASK_FILE (loaded at XCSoar startup)")
                }
            }
        }

        if (options.installAirspace) {
            if (asFile == null) {
                log("! no airspace file (.txt) published by the organiser")
            } else {
                val openAir = String(SoaringSpot.download(asFile), Charsets.UTF_8)
                if (parsed.inactiveAirspaces.isEmpty()) {
                    XCSoarTarget.write(target, AIRSPACE_FILE, openAir)
                    log("airspace: ${asFile.name} (no inactive airspace listed today)")
                } else {
                    val header = "* Airspace of the day — $slug ${task.cls} " +
                        "task ${task.number} (${task.date}) ${parsed.version}\n" +
                        "* ${parsed.inactiveAirspaces.size} inactive airspaces removed " +
                        "from ${asFile.name}\n"
                    val res = Airspace.filter(openAir, parsed.inactiveAirspaces, header)
                    XCSoarTarget.write(target, AIRSPACE_FILE, res.content)
                    val blocks = res.removed.values.sum()
                    log("airspace: $AIRSPACE_FILE — ${res.removed.size} removed ($blocks blocks)")
                    res.notFound.forEach {
                        log("! inactive airspace not found in the file: $it")
                        ok = false
                    }
                }
                airspaceInstalled = AIRSPACE_FILE
            }
        }

        if (options.updateProfile && (airspaceInstalled != null || waypointsInstalled != null)) {
            XCSoarTarget.setProfileFiles(target, airspaceInstalled, waypointsInstalled)
                .forEach { line ->
                    log(line)
                    if (line.startsWith("!")) ok = false
                }
            log("close XCSoar before installing — it rewrites default.prf when it exits")
        }
        return ok
    }
}
