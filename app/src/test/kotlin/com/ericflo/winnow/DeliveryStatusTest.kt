package com.ericflo.winnow

import android.provider.Telephony
import com.ericflo.winnow.sms.deliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryStatusTest {
    /** A CDMA report's status, as Android's 3GPP2 SmsMessage gives it: (error class << 8 | message status) << 16. */
    private fun cdma(errorClass: Int, messageStatus: Int) = ((errorClass shl 8) or messageStatus) shl 16

    @Test
    fun gsmStatusesByTheirRanges() {
        assertEquals(Telephony.Sms.STATUS_COMPLETE, deliveryStatus(0x00, "3gpp"))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, deliveryStatus(0x02, "3gpp"))
        assertEquals(Telephony.Sms.STATUS_PENDING, deliveryStatus(0x20, "3gpp"))
        assertEquals(Telephony.Sms.STATUS_FAILED, deliveryStatus(0x41, "3gpp"))
        // No format said: read as GSM, as before.
        assertEquals(Telephony.Sms.STATUS_COMPLETE, deliveryStatus(0x00, null))
    }

    @Test
    fun cdmaDeliveredIsComplete() {
        // Read as a GSM status, this (0x02 << 16) would have been a permanent failure.
        assertEquals(Telephony.Sms.STATUS_COMPLETE, deliveryStatus(cdma(0, 0x02), "3gpp2"))
    }

    @Test
    fun cdmaAcceptedIsStillPending() {
        assertEquals(Telephony.Sms.STATUS_PENDING, deliveryStatus(cdma(0, 0x00), "3gpp2"))
    }

    @Test
    fun cdmaErrorClasses() {
        assertEquals(Telephony.Sms.STATUS_PENDING, deliveryStatus(cdma(2, 0x04), "3gpp2"))
        assertEquals(Telephony.Sms.STATUS_FAILED, deliveryStatus(cdma(3, 0x05), "3gpp2"))
    }
}
