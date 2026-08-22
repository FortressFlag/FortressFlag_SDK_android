package com.fortressflag.example

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fortressflag.sdk.Configuration
import com.fortressflag.sdk.Environment
import com.fortressflag.sdk.FlagValue
import com.fortressflag.sdk.FortressFlag
import com.fortressflag.sdk.LogPolicy
import com.fortressflag.sdk.ObserverToken
import com.fortressflag.sdk.SignaturePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The example host: a real Android app reading real flags from the local backend — the
 * FlagListExample analogue, and the walkthrough vehicle for "done looks like".
 *
 * This app is a development fixture. Run the backend first (`FortressFlag_Backend`:
 * `make db-up migrate seed dev`), then run this in an emulator: `10.0.2.2` is the emulator's
 * fixed alias for the machine running it (the emulator cannot see `localhost` — that is the
 * emulated device itself). A physical device does not share the host's loopback and is out of
 * scope here.
 */
class MainActivity : Activity() {
    private lateinit var list: TextView
    private lateinit var diagnostics: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: ObserverToken? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FortressFlag.start(
            this,
            Configuration(
                // The key `make seed` installs — a committed fixture guarding a database on
                // a laptop, deliberately hardcoded so this app runs with zero setup. A
                // production app would paste its own key from Settings → SDK keys.
                sdkKey = "ffc_dev_seedseedseedseedseedseedseedseedseedseed000",
                environment = Environment.DEVELOPMENT,
                // Local development only; production uses the default HTTPS edge URL.
                baseUrl = "http://10.0.2.2:8080",
                // The backend does not sign payloads until M4 lands. Disabled is the
                // designed local-development path, not a shortcut — production keeps
                // Required.
                signaturePolicy = SignaturePolicy.Disabled,
                // The SDK's enforced minimum, so dashboard changes show up within half a
                // minute of a poll.
                refreshIntervalSeconds = 30,
                // Permits plaintext HTTP to loopback (and 10.0.2.2) only. Production never
                // sets this.
                allowsInsecureLocalTransport = true,
                // Verbose logs name flag keys in logcat — a dev tool, never for shipping.
                logging = LogPolicy.VERBOSE,
            ),
        )

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 64, 32, 32)

        val title = TextView(this)
        title.text = "FortressFlag flags"
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        list = TextView(this)
        list.setTypeface(Typeface.MONOSPACE)
        list.textSize = 14f
        root.addView(list)

        val refresh = Button(this)
        refresh.text = "Refresh now"
        refresh.setOnClickListener {
            scope.launch {
                FortressFlag.refresh()
                render()
            }
        }
        root.addView(refresh)

        val diagnosticsTitle = TextView(this)
        diagnosticsTitle.text = "Diagnostics"
        diagnosticsTitle.textSize = 22f
        diagnosticsTitle.setTypeface(null, Typeface.BOLD)
        root.addView(diagnosticsTitle)

        diagnostics = TextView(this)
        diagnostics.setTypeface(Typeface.MONOSPACE)
        diagnostics.textSize = 14f
        root.addView(diagnostics)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        observer =
            FortressFlag.onChange {
                runOnUiThread { render() }
            }
        render()
        // Keep the diagnostics clock honest while the poll runs in the background.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                render()
            }
        }
    }

    override fun onDestroy() {
        observer?.invalidate()
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val flags = FortressFlag.allFlags().toSortedMap()
        list.text =
            if (flags.isEmpty()) {
                "(no flags yet — cascade answers false)"
            } else {
                flags.entries.joinToString("\n") { (key, resolution) ->
                    val value =
                        when (val v = resolution.value) {
                            is FlagValue.Bool -> v.value.toString()
                            is FlagValue.Str -> "\"${v.value}\""
                            is FlagValue.Num -> v.value.toString()
                        }
                    "$key = $value  [${resolution.source.name.lowercase()}]"
                }
            }
        val d = FortressFlag.diagnostics
        val fetched = d.lastSuccessfulFetchEpochMillis?.let { Instant.ofEpochMilli(it).toString() } ?: "never"
        diagnostics.text =
            buildString {
                appendLine("started: ${d.isStarted}")
                appendLine("device: ${d.deviceIdentity ?: "(minting…)"}")
                appendLine("last successful fetch: $fetched")
                appendLine("fresh flags: ${d.freshFlagCount}, cached: ${d.cachedFlagCount}")
                appendLine("tag keys: ${d.sentTagKeys.joinToString(" ")}")
            }
    }
}
