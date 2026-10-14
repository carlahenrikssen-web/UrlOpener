package com.example.urlopener

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.urlopener.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("urlopener", MODE_PRIVATE) }

    // Foodora Sweden - verified Play Store package id
    private val FOODORA_PKG = "se.onlinepizza"

    class AppEntry(
        val label: String,
        val pkg: String,
        val icon: Drawable?,
        val isBrowser: Boolean
    ) {
        override fun toString() = label // shown in the closed spinner
    }

    private val apps = ArrayList<AppEntry>()

    private var exportFile: File? = null
    private val openExport = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* nothing to do */ }

    // file picker: load a text file (a url, or a list of urls) into the long-url field
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)!!
                    .bufferedReader().readText().trim()
                when {
                    text.isEmpty() -> toast("File is empty")
                    text.lines().size > 1 -> {
                        val urls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        AlertDialog.Builder(this)
                            .setTitle("Choose a URL from file")
                            .setItems(urls.toTypedArray()) { _, which ->
                                b.etLong.setText(urls[which])
                                b.tabs.getTabAt(0)?.select()
                            }.show()
                    }
                    else -> {
                        b.etLong.setText(text)
                        b.tabs.getTabAt(0)?.select()
                        toast("File loaded into Long URL field")
                    }
                }
            } catch (e: Exception) {
                toast("Could not read file: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        loadSettings()
        loadApps()
        renderHistory()

        b.tabs.addTab(b.tabs.newTab().setText("Long URL"))
        b.tabs.addTab(b.tabs.newTab().setText("Short URL"))
        b.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: com.google.android.material.tabs.TabLayout.Tab) { showPane() }
            override fun onTabUnselected(t: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(t: com.google.android.material.tabs.TabLayout.Tab) {}
        })
        showPane()

        b.btnPickFile.setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        b.btnPasteLong.setOnClickListener { pasteInto(b.etLong) }
        b.btnLongClear.setOnClickListener { b.etLong.setText("") }
        b.btnOpenLong.setOnClickListener { openIn(b.etLong.text.toString()) }

        b.btnPasteShort.setOnClickListener { pasteInto(b.etShort) }
        b.btnShortClear.setOnClickListener { b.etShort.setText("") }
        b.btnOpenShort.setOnClickListener { openShort(b.etShort.text.toString()) }

        b.chkLog.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("log", c).apply() }
        b.chkRemember.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("remember", c).apply() }
        b.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setPositiveButton("Clear") { _, _ -> prefs.edit().remove("history").apply(); renderHistory() }
                .setNegativeButton("Cancel", null).show()
        }
        b.btnExport.setOnClickListener { exportHistory() }

        // a link was shared to / opened from another app -> prefill and offer to open
        val incoming = urlFromIntent(intent)
        if (incoming != null) {
            b.etLong.setText(incoming)
            b.tabs.getTabAt(0)?.select()
            openIn(incoming)
        }
    }

    private fun urlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val s = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                return Regex("https?://\\S+").find(s)?.value
            }
            Intent.ACTION_VIEW -> return intent.data?.toString()
        }
        return null
    }

    private fun showPane() {
        val short = b.tabs.selectedTabPosition == 1
        b.paneLong.visibility = if (short) View.GONE else View.VISIBLE
        b.paneShort.visibility = if (short) View.VISIBLE else View.GONE
    }

    private fun pasteInto(et: android.widget.EditText) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val t = clip.getItemAt(0).coerceToText(this).toString().trim()
            if (t.isNotEmpty()) {
                et.setText(t)
                toast("Pasted")
            } else toast("Clipboard is empty")
        } else toast("Clipboard is empty")
    }

    // ---------- app picker list: browsers first, then ALL installed apps ----------
    private fun loadApps() {
        apps.clear()
        val pm = packageManager
        val seen = HashSet<String>()

        // 1. every app that registered for http/https links (browsers, webviews)
        val browse = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers: List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= 33)
                pm.queryIntentActivities(browse, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            else @Suppress("DEPRECATION")
            pm.queryIntentActivities(browse, PackageManager.MATCH_ALL)

        for (ri in browsers) {
            if (ri.activityInfo.packageName == packageName) continue
            if (seen.add(ri.activityInfo.packageName)) {
                apps.add(AppEntry(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri.loadIcon(pm), true))
            }
        }

        // 2. every other launchable app (Foodora, banks, games... anything)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables: List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= 33)
                pm.queryIntentActivities(launch, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            else @Suppress("DEPRECATION")
            pm.queryIntentActivities(launch, PackageManager.MATCH_ALL)

        val others = ArrayList<AppEntry>()
        for (ri in launchables) {
            if (ri.activityInfo.packageName == packageName) continue
            if (seen.add(ri.activityInfo.packageName)) {
                others.add(AppEntry(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri.loadIcon(pm), false))
            }
        }
        others.sortBy { it.label.lowercase() }
        apps.addAll(others)

        // spinner: "Ask every time" + all apps, with icons
        val spinnerItems = ArrayList<AppEntry>()
        spinnerItems.add(AppEntry("Ask every time (Android chooser)", "", null, true))
        spinnerItems.addAll(apps)
        b.spinnerApp.adapter = object : ArrayAdapter<AppEntry>(this, R.layout.item_app, spinnerItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                rowView(position, convertView, parent)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                rowView(position, convertView, parent)
            private fun rowView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val item = spinnerItems[position]
                v.findViewById<TextView>(R.id.tvLabel).text = item.label
                val iv = v.findViewById<ImageView>(R.id.ivIcon)
                if (item.icon != null) {
                    iv.visibility = View.VISIBLE
                    iv.setImageDrawable(item.icon)
                } else iv.visibility = View.GONE
                return v
            }
        }

        // default choice: saved pick -> Foodora if installed -> Ask every time
        val saved = prefs.getString("default_app", null)
        var idx = 0
        if (saved != null) {
            val i = spinnerItems.indexOfFirst { it.pkg == saved }
            if (i >= 0) idx = i
        } else {
            val fi = spinnerItems.indexOfFirst { it.pkg == FOODORA_PKG }
            if (fi >= 0) idx = fi
        }
        b.spinnerApp.setSelection(idx)
        b.spinnerApp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var current = idx
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == current) return
                current = pos
                prefs.edit().putString("default_app", spinnerItems[pos].pkg).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun resolveUrl(raw: String, isShortTab: Boolean): String? {
        var url = raw.trim()
        if (url.isEmpty()) { toast("Paste a URL first"); return null }
        if (isShortTab) {
            if (!url.startsWith("http")) url = "https://shorturl.at/$url"
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    // ---------- open in the app picked in Step 1 ----------
    private fun openInApp(url: String) {
        val sel = b.spinnerApp.selectedItem as? AppEntry
        val target = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        // no default chosen -> standard Android chooser
        if (sel == null || sel.pkg.isEmpty()) {
            try {
                startActivity(Intent.createChooser(target, "Open with..."))
            } catch (e: ActivityNotFoundException) {
                toast("No app can open this link")
            }
            return
        }

        // chosen app: force the URL into it
        try {
            startActivity(target.setPackage(sel.pkg))
            return
        } catch (e: Exception) { /* app has no link handler, try launching the app itself */ }

        // non-browser app (e.g. Foodora): open the app and tell the user
        val launch = packageManager.getLaunchIntentForPackage(sel.pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launch)
                toast("${sel.label} opened (it may not accept links directly)")
            } catch (e: Exception) {
                toast("Could not open ${sel.label}")
            }
        } else {
            toast("${sel.label} cannot open links")
        }
    }

    private fun openIn(raw: String) {
        val url = resolveUrl(raw, b.tabs.selectedTabPosition == 1) ?: return
        if (b.chkRemember.isChecked && b.tabs.selectedTabPosition == 0) {
            prefs.edit().putString("last_long", url).apply()
        }
        saveHistory(url)
        openInApp(url)
    }

    private fun openShort(raw: String) {
        val url = resolveUrl(raw, true) ?: return
        if (b.chkRemember.isChecked) {
            val code = url.substringAfterLast('/')
            b.etShort.setText(code)
            prefs.edit().putString("last_short", code).apply()
        }
        saveHistory(url)
        openInApp(url)
    }

    // ---------- history ----------
    private fun saveHistory(url: String) {
        if (!b.chkLog.isChecked) return
        val list = history()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        list.add(0, "$time  |  $url")
        while (list.size > 50) list.removeAt(list.size - 1)
        prefs.edit().putString("history", list.joinToString("\n")).apply()
        renderHistory()
    }

    private fun history(): MutableList<String> {
        val raw = prefs.getString("history", "") ?: ""
        return if (raw.isEmpty()) mutableListOf() else raw.split("\n").toMutableList()
    }

    private fun renderHistory() {
        val list = history()
        b.tvCount.text = "${list.size} saved link(s)"
        b.btnExport.isEnabled = list.isNotEmpty()
        b.btnClearHistory.isEnabled = list.isNotEmpty()
        b.tvHistory.text = if (list.isEmpty()) "No history yet." else list.joinToString("\n\n")
    }

    private fun exportHistory() {
        val list = history()
        if (list.isEmpty()) return
        val dir = File(getExternalFilesDir(null), "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val f = File(dir, "urlopener_history_$stamp.txt")
        f.writeText(list.joinToString("\n") + "\n")
        exportFile = f
        toast("Saved: ${f.name}")
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                FileProvider.getUriForFile(this, "$packageName.fileprovider", f),
                "text/plain"
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            openExport.launch(Intent.createChooser(view, "Open history file"))
        } catch (e: ActivityNotFoundException) { /* file is still saved */ }
    }

    private fun loadSettings() {
        b.chkLog.isChecked = prefs.getBoolean("log", true)
        b.chkRemember.isChecked = prefs.getBoolean("remember", true)
        prefs.getString("last_long", "")?.let { if (it.isNotEmpty()) b.etLong.setText(it) }
        prefs.getString("last_short", "")?.let { if (it.isNotEmpty()) b.etShort.setText(it) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
