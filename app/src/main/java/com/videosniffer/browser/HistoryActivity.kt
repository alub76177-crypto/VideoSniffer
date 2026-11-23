package com.videosniffer.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class HistoryActivity : Activity() {

    private lateinit var store: HistoryStore
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = HistoryStore(this)

        window.statusBarColor = Color.rgb(250, 250, 252)
        window.navigationBarColor = Color.rgb(250, 250, 252)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(250, 250, 252))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            TextView(this).apply {
                text = "History"
                textSize = 21f
                setTextColor(Color.rgb(25, 27, 35))
            },
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        header.addView(
            Button(this).apply {
                text = "Clear"
                setOnClickListener {
                    store.clear()
                    render()
                }
            }
        )

        root.addView(header)

        empty = TextView(this).apply {
            text = "No history yet."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(dp(8), dp(18), dp(8), dp(18))
        }

        root.addView(empty)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(
            Button(this).apply {
                text = "Back to browser"
                setOnClickListener { finish() }
            }
        )

        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()

        val items = store.getAll()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        items.forEach { entry ->

            if (!entry.dateHeader.isNullOrEmpty()) {
                list.addView(
                    TextView(this).apply {
                        text = entry.dateHeader
                        textSize = 13f
                        setTextColor(Color.rgb(75, 65, 130))
                        setPadding(dp(4), dp(12), 0, dp(4))
                    }
                )
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setBackgroundColor(Color.WHITE)
                setOnClickListener {
                    setResult(
                        RESULT_OK,
                        Intent().putExtra("selected_url", entry.url)
                    )
                    finish()
                }
            }

            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            texts.addView(
                TextView(this).apply {
                    text = entry.title.ifEmpty { entry.url }
                    textSize = 15f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(Color.rgb(25, 27, 35))
                }
            )

            texts.addView(
                TextView(this).apply {
                    text = hostOf(entry.url)
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(Color.GRAY)
                }
            )

            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

            row.addView(
                TextView(this).apply {
                    text = "\u2715"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.DKGRAY)
                    setOnClickListener {
                        store.delete(entry.id)
                        render()
                    }
                },
                LinearLayout.LayoutParams(dp(44), dp(44))
            )

            list.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }
            )
        }
    }

    private fun hostOf(url: String): String =
        try {
            Uri.parse(url).host ?: url
        } catch (_: Exception) {
            url
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
