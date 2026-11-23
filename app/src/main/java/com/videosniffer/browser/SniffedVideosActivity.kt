package com.videosniffer.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SniffedVideosActivity : Activity() {

    private var tabId: Long = 1L

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    private fun manager() = MainActivity.sharedManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tabId = intent.getLongExtra(
            "tabId",
            MainActivity.activeTabId
        )

        window.statusBarColor = Color.rgb(250, 250, 252)
        window.navigationBarColor = Color.rgb(250, 250, 252)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

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
                text = "Detected media links"
                textSize = 21f
                setTextColor(Color.rgb(25, 27, 35))
            },
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        header.addView(
            Button(this).apply {
                text = "Clear"
                setOnClickListener {
                    manager()?.clear(tabId)
                    render()
                }
            }
        )

        root.addView(header)

        empty = TextView(this).apply {
            text = "No media links detected yet. Play a video on a webpage, then return here."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(dp(8), dp(18), dp(8), dp(18))
        }

        root.addView(empty)

        val scroll = ScrollView(this)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(list)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            Button(this).apply {
                text = "Back to browser"
                setOnClickListener {
                    finish()
                }
            }
        )

        setContentView(root)

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()

        val items = manager()?.getItems(tabId).orEmpty()

        empty.visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE

        items.forEach { media ->

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.WHITE)
                elevation = dp(1).toFloat()
            }

            card.addView(
                TextView(this).apply {
                    text =
                        "${media.type.label}${if (media.isMaster) " (Master)" else ""} · ${media.status.label} · ${media.confidence.label}"
                    textSize = 14f
                    setTextColor(Color.rgb(75, 65, 130))
                }
            )

            card.addView(
                TextView(this).apply {
                    text = media.host
                    textSize = 13f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, dp(4), 0, dp(3))
                }
            )

            card.addView(
                TextView(this).apply {
                    text = media.shortUrl
                    textSize = 13f
                    setTextColor(Color.rgb(25, 45, 80))
                    setTextIsSelectable(true)
                }
            )

            card.addView(
                TextView(this).apply {
                    text = "Page: ${media.pageTitle}"
                    textSize = 12f
                    setTextColor(Color.GRAY)
                    setPadding(0, dp(4), 0, dp(5))
                }
            )

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            // Copy media URL
            buttons.addView(
                Button(this@SniffedVideosActivity).apply {
                    text = "Copy"

                    setOnClickListener {
                        val clipboard =
                            getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager

                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Media URL",
                                media.url
                            )
                        )

                        Toast.makeText(
                            this@SniffedVideosActivity,
                            "Link copied",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            // Share media URL
            buttons.addView(
                Button(this@SniffedVideosActivity).apply {
                    text = "Share"

                    setOnClickListener {
                        val shareIntent = Intent(
                            Intent.ACTION_SEND
                        ).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                media.url
                            )
                        }

                        startActivity(
                            Intent.createChooser(
                                shareIntent,
                                "Share media link"
                            )
                        )
                    }
                }
            )

            // Open media URL
            buttons.addView(
                Button(this@SniffedVideosActivity).apply {
                    text = "Open"

                    setOnClickListener {
                        try {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(media.url)
                                )
                            )
                        } catch (_: Exception) {
                            Toast.makeText(
                                this@SniffedVideosActivity,
                                "No app can open this link",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )

            card.addView(buttons)

            list.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            )
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density + 0.5f
        ).toInt()
    }
}