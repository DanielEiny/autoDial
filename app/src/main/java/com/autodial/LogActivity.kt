package com.autodial

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autodial.databinding.ActivityLogBinding
import kotlinx.coroutines.launch

class LogActivity : AppCompatActivity() {
    private lateinit var b: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.copy.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("AutoDial log", shareableText()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        b.share.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "AutoDial log")
                .putExtra(Intent.EXTRA_TEXT, shareableText())
            startActivity(Intent.createChooser(send, getString(R.string.share_log)))
        }
        b.clear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_confirm)
                .setPositiveButton(R.string.clear) { _, _ -> EventLog.clear() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventLog.version.collect {
                    b.logText.text = EventLog.read()
                    b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
        }
    }

    /** Clipboard/Intent extras go through Binder (~1 MB limit), so keep the newest part only. */
    private fun shareableText(): String {
        val t = EventLog.read()
        return if (t.length <= MAX_SHARE_CHARS) t else "…(older lines omitted)\n" + t.takeLast(MAX_SHARE_CHARS)
    }

    private companion object {
        const val MAX_SHARE_CHARS = 200_000
    }
}
