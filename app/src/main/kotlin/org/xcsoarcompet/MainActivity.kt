package org.xcsoarcompet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val PAD = 32
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var storageButton: Button
    private lateinit var targetSpinner: Spinner
    private lateinit var targetWarning: TextView
    private lateinit var compInput: EditText
    private lateinit var searchButton: Button
    private lateinit var loadButton: Button
    private lateinit var taskSpinner: Spinner
    private lateinit var installButton: Button
    private lateinit var logView: TextView
    private lateinit var progress: ProgressBar

    private lateinit var optTask: CheckBox
    private lateinit var optDefault: CheckBox
    private lateinit var optWaypoints: CheckBox
    private lateinit var optAirspace: CheckBox
    private lateinit var optProfile: CheckBox

    private var targets: List<XCSoarTarget.Target> = emptyList()
    private var tasks: List<SoaringSpot.TaskRef> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshStorageState()
    }

    override fun onResume() {
        super.onResume()
        refreshStorageState()
    }

    // ------------------------------------------------------------ interface --

    private fun pad(v: View) = v.apply { setPadding(0, 8, 0, 8) }

    /**
     * Depuis Android 15, une application ciblant le SDK 35 dessine
     * obligatoirement sous les barres système. Sans cette marge, le premier
     * élément — le bouton d'autorisation — se retrouve caché derrière la barre
     * d'état, et l'utilisateur ne peut plus accorder l'accès aux fichiers.
     */
    private fun applySystemBarInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(PAD + bars.left, PAD + bars.top, PAD + bars.right, PAD + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    PAD + insets.systemWindowInsetLeft, PAD + insets.systemWindowInsetTop,
                    PAD + insets.systemWindowInsetRight, PAD + insets.systemWindowInsetBottom
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD, PAD, PAD, PAD)
        }
        applySystemBarInsets(root)

        storageButton = Button(this).apply {
            text = getString(R.string.grant_storage)
            setOnClickListener { requestAllFilesAccess() }
        }
        root.addView(storageButton)

        root.addView(pad(TextView(this).apply { text = getString(R.string.target_label) }))
        targetSpinner = Spinner(this)
        root.addView(targetSpinner)
        targetWarning = TextView(this).apply {
            setTextColor(0xFFB00020.toInt())
            visibility = View.GONE
            setOnClickListener {
                // tant que l'autorisation manque, l'avertissement mène au réglage,
                // pas à la page d'aide : c'est l'action utile à ce moment-là
                if (!hasAllFilesAccess()) requestAllFilesAccess()
                else startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(XCSoarTarget.STORAGE_HELP_URL))
                )
            }
        }
        root.addView(pad(targetWarning))

        root.addView(pad(TextView(this).apply { text = getString(R.string.competition_label) }))
        compInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.competition_hint)
            setSingleLine()
        }
        root.addView(compInput)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val half = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        searchButton = Button(this).apply {
            text = getString(R.string.search)
            setOnClickListener { searchCompetitions() }
        }
        loadButton = Button(this).apply {
            text = getString(R.string.load_tasks)
            setOnClickListener { loadTasks() }
        }
        buttons.addView(searchButton, half)
        buttons.addView(loadButton, half)
        root.addView(buttons)

        root.addView(pad(TextView(this).apply { text = getString(R.string.task_label) }))
        taskSpinner = Spinner(this)
        root.addView(taskSpinner)

        optTask = CheckBox(this).apply { text = getString(R.string.opt_task); isChecked = true }
        optDefault = CheckBox(this).apply { text = getString(R.string.opt_default); isChecked = true }
        optWaypoints = CheckBox(this).apply { text = getString(R.string.opt_waypoints); isChecked = true }
        optAirspace = CheckBox(this).apply { text = getString(R.string.opt_airspace); isChecked = true }
        optProfile = CheckBox(this).apply { text = getString(R.string.opt_profile); isChecked = true }
        listOf(optTask, optDefault, optWaypoints, optAirspace, optProfile).forEach { root.addView(it) }

        installButton = Button(this).apply {
            text = getString(R.string.install)
            isEnabled = false
            setOnClickListener { install() }
        }
        root.addView(installButton)

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress)

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val footer = TextView(this).apply {
            text = getString(R.string.restart_hint)
            gravity = Gravity.CENTER
        }
        root.addView(pad(footer), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return root
    }

    private fun log(line: String) = ui.post {
        logView.append(line + "\n")
    }

    private fun busy(on: Boolean) = ui.post {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        loadButton.isEnabled = !on
        searchButton.isEnabled = !on
        installButton.isEnabled = !on && tasks.isNotEmpty()
    }

    // -------------------------------------------------------------- stockage --

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun refreshStorageState() {
        val granted = hasAllFilesAccess()
        storageButton.visibility = if (granted) View.GONE else View.VISIBLE
        if (!granted) {
            targetWarning.text = getString(R.string.need_storage)
            targetWarning.visibility = View.VISIBLE
            return
        }
        targets = XCSoarTarget.discover()
        targetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            if (targets.isEmpty()) listOf(getString(R.string.no_target))
            else targets.map { it.packageName }
        )
        targetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                updateTargetWarning()

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateTargetWarning()
    }

    private fun currentTarget(): XCSoarTarget.Target? =
        targets.getOrNull(targetSpinner.selectedItemPosition)

    private fun updateTargetWarning() {
        val t = currentTarget()
        when {
            t == null -> {
                targetWarning.text = getString(R.string.no_target_help)
                targetWarning.visibility = View.VISIBLE
            }
            !t.hasEvidenceOfUse -> {
                targetWarning.text = getString(R.string.target_unused_warning)
                targetWarning.visibility = View.VISIBLE
            }
            else -> targetWarning.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------- actions ---

    /**
     * Recherche par nom, lieu ou année. Le champ vide liste les compétitions
     * en cours : c'est le cas d'usage le plus fréquent au briefing.
     */
    private fun searchCompetitions() {
        val query = compInput.text.toString().trim()
        busy(true)
        io.execute {
            try {
                val found = SoaringSpot.search(query)
                ui.post {
                    if (found.isEmpty()) {
                        log("! no competition found for \"$query\"")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.pick_competition))
                            .setItems(found.map { it.toString() }.toTypedArray()) { _, which ->
                                val c = found[which]
                                compInput.setText(c.slug)
                                log("${c.name} (${c.slug})")
                                loadTasks()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                log("! ${e.message}")
            } finally {
                busy(false)
            }
        }
    }

    private fun loadTasks() {
        val slug = SoaringSpot.slugOf(compInput.text.toString())
        if (slug.isEmpty()) {
            log("! enter a competition URL or name")
            return
        }
        busy(true)
        io.execute {
            try {
                val found = SoaringSpot.listTasks(slug)
                ui.post {
                    tasks = found
                    taskSpinner.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item,
                        found.map { "${it.cls} — ${it.label}" }
                    )
                    if (found.isNotEmpty()) taskSpinner.setSelection(found.size - 1)
                    installButton.isEnabled = found.isNotEmpty()
                }
                log(
                    if (found.isEmpty()) "! no task published for \"$slug\""
                    else "$slug: ${found.size} tasks published"
                )
            } catch (e: Exception) {
                log("! ${e.message}")
            } finally {
                busy(false)
            }
        }
    }

    private fun install() {
        val target = currentTarget() ?: run { log("! no XCSoar folder selected"); return }
        val task = tasks.getOrNull(taskSpinner.selectedItemPosition)
            ?: run { log("! no task selected"); return }
        val slug = SoaringSpot.slugOf(compInput.text.toString())
        val options = Installer.Options(
            installTask = optTask.isChecked,
            setAsDefaultTask = optDefault.isChecked,
            installWaypoints = optWaypoints.isChecked,
            installAirspace = optAirspace.isChecked,
            updateProfile = optProfile.isChecked,
        )
        busy(true)
        io.execute {
            try {
                val clean = Installer(slug, task, target, options) { log(it) }.run()
                log(if (clean) "--- done" else "--- done, with warnings above")
            } catch (e: Exception) {
                log("! ${e.message}")
            } finally {
                busy(false)
            }
        }
    }
}
