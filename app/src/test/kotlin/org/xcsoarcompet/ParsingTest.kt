package org.xcsoarcompet

import org.junit.Assert.*
import org.junit.Test

/**
 * Les fixtures sont des pages SoaringSpot réelles (Championnat de France 2026,
 * Villefranche-Tarare), enregistrées telles quelles. Elles servent de garde-fou
 * : si SoaringSpot change son gabarit, ces tests tombent avant les pilotes.
 */
class ParsingTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .readBytes().toString(Charsets.UTF_8)

    // ------------------------------------------------------------- CUP ------

    @Test
    fun `champ cite contenant une virgule ne coupe pas la ligne`() {
        val f = Cup.splitLine("\"Terrain, piège\",\"LF01\",FR,4542.467N,00201.950E,760m")
        assertEquals(6, f.size)
        assertEquals("Terrain, piège", f[0])
        assertEquals("LF01", f[1])
    }

    @Test
    fun `guillemet double echappe`() {
        val f = Cup.splitLine("\"dit \"\"Bravo\"\"\",\"X\",FR")
        assertEquals("dit \"Bravo\"", f[0])
        assertEquals("X", f[1])
    }

    @Test
    fun `conversion des coordonnees CUP`() {
        assertEquals(45.70778, Cup.parseCoord("4542.467N")!!, 1e-5)
        assertEquals(2.0325, Cup.parseCoord("00201.950E")!!, 1e-5)
        assertEquals(-0.001945, Cup.parseCoord("00000.117W")!!, 1e-5)
        assertNull(Cup.parseCoord("pas une coordonnée"))
    }

    @Test
    fun `lecture du fichier de points de virage`() {
        val points = Cup.parse(fixture("waypoints-extract.cup"))
        val dep = points["003 DEP3"]!!
        assertEquals(45.9628, dep.lat, 1e-4)
        assertEquals(4.5233, dep.lon, 1e-4)
        assertEquals(356.0, dep.altitude, 0.5)
    }

    // ------------------------------------------------------- page « task » --

    @Test
    fun `task RT complete`() {
        val t = SoaringSpot.parseTask(fixture("task5-rt.html"))
        assertEquals("v2", t.version)
        assertEquals(6, t.points.size)
        assertEquals("006 Modelistes", t.points.first().name)
        assertEquals("000 ARRIVEE", t.points.last().name)
        assertFalse(t.isAat)
        assertEquals(12, t.inactiveAirspaces.size)
        assertTrue(t.inactiveAirspaces.contains("PARA Chalon"))
        assertTrue(t.inactiveAirspaces.contains("CTR SAINT YAN 2 (MON-FRI HX)"))
    }

    @Test
    fun `task AAT avec duree minimale`() {
        val t = SoaringSpot.parseTask(fixture("task1-aat.html"))
        assertEquals("v4", t.version)
        assertTrue(t.isAat)
        assertEquals(7200, t.aatSeconds)
        assertEquals(5, t.points.size)
        assertEquals(10, t.inactiveAirspaces.size)
    }

    @Test
    fun `regles du jour extraites des notes`() {
        val t = SoaringSpot.parseTask(fixture("task1-aat.html"))
        val r = TaskXml.parseRules(t.notes)
        assertEquals(170 / 3.6, r.startMaxSpeed!!, 1e-4)
        assertEquals(600, r.finishMinHeight)
        // « donnée en vol avant le départ » : rien à chiffrer
        assertNull(r.startMaxHeight)
    }

    @Test
    fun `fichiers de l organisateur`() {
        val files = Regex("href=\"(/en_gb/download-contest-file/[^\"]+)\">(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(fixture("downloads.html"))
            .map { SoaringSpot.ContestFile(Html.text(it.groupValues[2]), it.groupValues[1]) }
            .toList()
        assertEquals("TP-CDF-Club-Villefranche-2026-v1.0.cup",
            SoaringSpot.waypointFile(files)!!.name)
        assertEquals("AS-CDF-Club-Villefranche-2026-v1.1.txt",
            SoaringSpot.airspaceFile(files)!!.name)
    }

    // ------------------------------------------------- zones d'observation --

    @Test
    fun `zones d observation traduites`() {
        assertEquals("<ObservationZone length=\"10000\" type=\"Line\"/>",
            TaskXml.observationZone("Line 10.00 km (Radius 5.00 km)"))
        assertEquals("<ObservationZone radius=\"3000\" type=\"Cylinder\"/>",
            TaskXml.observationZone("Cylinder R=3.00 km"))
        assertEquals(
            "<ObservationZone angle=\"90\" radius=\"20000\" inner_radius=\"500\" type=\"CustomKeyhole\"/>",
            TaskXml.observationZone(
                "enum.label.result_status.symmetric, Rmin=0.50 km, Rmax=20.00 km, " +
                    "Angle=90.0°, Cylinder R=0.50 km"
            )
        )
    }

    @Test
    fun `secteur de 360 degres devient un cylindre`() {
        assertEquals("<ObservationZone radius=\"60000\" type=\"Cylinder\"/>",
            TaskXml.observationZone(
                "enum.label.result_status.symmetric, Rmin=0.50 km, Rmax=60.00 km, " +
                    "Angle=360.0°, Cylinder R=0.50 km"
            ))
    }

    // ------------------------------------------------------------ circuit ---

    @Test
    fun `circuit AAT genere`() {
        val t = SoaringSpot.parseTask(fixture("task1-aat.html"))
        val wp = Cup.parse(fixture("waypoints-extract.cup"))
        val xml = TaskXml.build(t, wp, TaskXml.parseRules(t.notes))
        assertTrue(xml.contains("type=\"AAT\""))
        assertTrue(xml.contains("aat_min_time=\"7200\""))
        assertTrue(xml.contains("start_max_speed=\"47.2222\""))
        assertTrue(xml.contains("finish_min_height=\"600\""))
        assertTrue(xml.contains("<Point type=\"Start\">"))
        assertTrue(xml.contains("<Point type=\"Finish\">"))
        assertEquals(3, Regex("<Point type=\"Turn\">").findAll(xml).count())
        assertTrue(xml.contains("<ObservationZone radius=\"30000\" type=\"Cylinder\"/>"))
    }

    @Test
    fun `point absent du fichier de waypoints est signale`() {
        val t = SoaringSpot.parseTask(fixture("task1-aat.html"))
        try {
            TaskXml.build(t, emptyMap(), TaskXml.Rules())
            fail("devait lever MissingPointException")
        } catch (e: TaskXml.MissingPointException) {
            assertEquals(5, e.names.size)
        }
    }

    // ---------------------------------------------------- espaces aériens ---

    @Test
    fun `espaces inactifs retires par nom exact`() {
        val openAir = listOf(
            "* entête",
            "AC R", "AN TMA LYON 3", "AL SFC", "AH FL65", "DP 45:00:00 N 004:00:00 E",
            "AC R", "AN TMA LYON 3 DES R3201B", "AL SFC", "AH FL65",
            "AC D", "AN PARA Chalon", "AL SFC", "AH FL65",
        ).joinToString("\n")

        val r = Airspace.filter(openAir, "TMA LYON 3 DES R3201B, PARA Chalon", "")

        assertEquals(2, r.removed.size)
        assertTrue(r.notFound.isEmpty())
        // le voisin au nom plus court doit survivre
        assertTrue(r.content.contains("AN TMA LYON 3\n"))
        assertFalse(r.content.contains("R3201B"))
        assertFalse(r.content.contains("PARA Chalon"))
        assertTrue(r.content.startsWith("* entête"))
    }

    @Test
    fun `espace inactif introuvable est rapporte`() {
        val openAir = "AC R\nAN TMA LYON 3\nAL SFC\n"
        val r = Airspace.filter(openAir, "ZONE INEXISTANTE", "")
        assertEquals(listOf("ZONE INEXISTANTE"), r.notFound)
        assertTrue(r.removed.isEmpty())
    }

    // ------------------------------------------------------------- divers ---

    @Test
    fun `slug extrait d une URL`() {
        assertEquals("cdf2026-villefranche",
            SoaringSpot.slugOf("https://www.soaringspot.com/en_gb/cdf2026-villefranche/"))
        assertEquals("cdf2026-villefranche",
            SoaringSpot.slugOf("https://www.soaringspot.com/fr/cdf2026-villefranche/results/club"))
        assertEquals("cdf2026-villefranche", SoaringSpot.slugOf("cdf2026-villefranche"))
    }

    @Test
    fun `entites html decodees`() {
        assertEquals("4.14 km", Html.text("4.14&nbsp;km"))
        assertEquals("143.6°", Html.text("143.6&deg;"))
        assertEquals("MuséeAvion", Html.text("Mus&eacute;eAvion"))
        assertEquals("a<b>c", Html.text("a&lt;<i>b</i>&gt;c"))
    }

    @Test
    fun `recherche de competitions`() {
        val comps = SoaringSpot.parseCompetitions(fixture("search.html"))
        assertTrue(comps.isNotEmpty())
        val c = comps.first { it.slug == "cdf2026-villefranche" }
        assertTrue(c.name.startsWith("Championnat de France 2026"))
        assertTrue(c.info.contains("Villefranche-Tarare"))
    }

    // ------------------------------------------------------------- profil ---

    @Test
    fun `cle existante remplacee`() {
        val prf = "AirspaceFileList=\"%LOCAL_PATH%\\France.txt\"\nMapFile=\"%LOCAL_PATH%\\FR.xcm\"\n"
        val out = XCSoarTarget.writeValue(prf, "AirspaceFileList", "%LOCAL_PATH%\\compet.txt")
        assertTrue(out.contains("AirspaceFileList=\"%LOCAL_PATH%\\compet.txt\""))
        assertTrue(out.contains("MapFile="))
        assertEquals(1, Regex("AirspaceFileList=").findAll(out).count())
    }

    @Test
    fun `cle absente ajoutee`() {
        val out = XCSoarTarget.writeValue("MapFile=\"x\"\n", "WPFileList", "a.cup")
        assertTrue(out.contains("MapFile=\"x\""))
        assertTrue(out.contains("WPFileList=\"a.cup\""))
    }




    @Test
    fun `lecture de cle avec repli sur l ancien nom`() {
        val prf = "WPFile=\"%LOCAL_PATH%\\FR.cup\"\n"
        val (key, value) = XCSoarTarget.readValue(prf, listOf("WPFileList", "WPFile"))!!
        assertEquals("WPFile", key)
        assertEquals("%LOCAL_PATH%\\FR.cup", value)
        assertNull(XCSoarTarget.readValue(prf, listOf("Inexistant")))
    }

    @Test
    fun `profil reel d un pilote patche correctement`() {
        val dir = java.io.File.createTempFile("xcsoar", "").let {
            it.delete(); it.mkdirs(); it
        }
        java.io.File(dir, "default.prf").writeText(fixture("default.prf"))
        val target = XCSoarTarget.Target(dir)

        val messages = XCSoarTarget.setProfileFiles(
            target, "compet_airspace.txt", "TP-CDF-Club-Villefranche-2026-v1.0.cup"
        )
        val out = java.io.File(dir, "default.prf").readText()

        // les espaces du jour remplacent le fichier national
        assertTrue(out.contains("AirspaceFileList=\"%LOCAL_PATH%\\compet_airspace.txt\""))
        assertFalse(out.contains("France-2026-04-16-AirSpace.txt"))
        assertTrue(messages.any { it.contains("previous airspace was") })

        // le fichier de l'organisateur devient la seule référence
        assertTrue(out.contains(
            "WPFileList=\"%LOCAL_PATH%\\TP-CDF-Club-Villefranche-2026-v1.0.cup\""))
        assertFalse(out.contains("FR.cup"))
        assertTrue(messages.any { it.contains("previous waypoints was") })

        // le reste du profil est intact
        assertTrue(out.contains("MapFile=\"%LOCAL_PATH%\\FR.xcm\""))
        assertEquals(fixture("default.prf").lines().size, out.lines().size)

        // relancer deux fois ne duplique rien
        XCSoarTarget.setProfileFiles(
            target, "compet_airspace.txt", "TP-CDF-Club-Villefranche-2026-v1.0.cup")
        val twice = java.io.File(dir, "default.prf").readText()
        assertEquals(1, Regex("TP-CDF").findAll(twice).count())
        dir.deleteRecursively()
    }

    @Test
    fun `nom d espace contenant des virgules`() {
        val openAir = listOf(
            "AC R", "AN LRR206 - Active H24, except SAR, Police, Medevac", "AL SFC",
            "AC R", "AN LRR7 - Active H24", "AL SFC",
            "AC R", "AN LRR8 - Active H24 - except state aircraft", "AL SFC",
        ).joinToString("\n")
        val listed = "LRR206 - Active H24, except SAR, Police, Medevac, " +
            "LRR8 - Active H24 - except state aircraft"

        val r = Airspace.filter(openAir, listed, "")

        assertEquals(2, r.removed.size)
        assertTrue(r.notFound.isEmpty())
        assertFalse(r.content.contains("LRR206"))
        assertFalse(r.content.contains("LRR8"))
        // celui qui n'est pas listé reste
        assertTrue(r.content.contains("AN LRR7 - Active H24"))
    }
}
