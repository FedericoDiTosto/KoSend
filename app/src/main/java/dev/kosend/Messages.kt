package dev.kosend

import android.content.Context

object Messages {
    const val NO_PORT = "#no_port"
    const val MISSING_FILE = "#missing_file"
    const val REJECTED = "#rejected"
    private const val UNSUPPORTED = "#unsupported:"

    fun unsupported(extension: String) = UNSUPPORTED + extension

    fun localize(context: Context, message: String): String = when {
        message == NO_PORT -> context.getString(R.string.error_no_port)
        message == MISSING_FILE -> context.getString(R.string.error_missing_file)
        message == REJECTED -> context.getString(R.string.error_rejected)
        message.startsWith(UNSUPPORTED) ->
            context.getString(R.string.error_unsupported, message.removePrefix(UNSUPPORTED))
        else -> message
    }
}
