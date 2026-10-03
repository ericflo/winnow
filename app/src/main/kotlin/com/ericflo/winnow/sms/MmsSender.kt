package com.ericflo.winnow.sms

import android.content.Context
import com.ericflo.winnow.data.OutgoingAttachment

/** Sends MMS: group messages and attachments. Wired to the PDU codec in the MMS phase. */
class MmsSender(private val context: Context) {
    fun send(recipients: List<String>, body: String, attachments: List<OutgoingAttachment>) {
        throw UnsupportedOperationException("MMS sending is not wired up yet")
    }

    fun retry(mmsId: Long) {
        throw UnsupportedOperationException("MMS sending is not wired up yet")
    }
}
