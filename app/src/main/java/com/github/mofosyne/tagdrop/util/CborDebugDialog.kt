package com.github.mofosyne.tagdrop.util

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.mofosyne.tagdrop.R
import com.github.mofosyne.tagdrop.data.format.TagDropCodec

/** Shows the raw CBOR bytes for [cbor] in a dialog, annotated with field names. */
fun Context.showCborDebugDialog(cbor: ByteArray, title: String) {
    val view = LayoutInflater.from(this).inflate(R.layout.dialog_cbor_debug, null)
    view.findViewById<TextView>(R.id.textCborDump).text = TagDropCodec.describeCbor(cbor)
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.cbor_debug_title, title))
        .setView(view)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
