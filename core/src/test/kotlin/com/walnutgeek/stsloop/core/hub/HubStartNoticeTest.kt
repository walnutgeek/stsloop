package com.walnutgeek.stsloop.core.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HubStartNoticeTest {
    private val said = mutableListOf<String>()
    private val notice = HubStartNotice { said += it }
    private val welcome = Protocol.decode(Fixtures.text("v1", "welcome/basic.json")) as Welcome

    @Test
    fun `the first failure of a Session is said once`() {
        notice.status(HubStatus.Connecting)
        notice.status(HubStatus.Offline("x", HubFailure.Unreachable("a:1")))
        notice.status(HubStatus.Connecting)
        notice.status(HubStatus.Offline("x", HubFailure.NoWelcome))
        assertEquals(listOf("can't reach the hub."), said)
    }

    @Test
    fun `a refusal or a wrong token is said the same way`() {
        notice.status(HubStatus.Unauthorised)
        assertEquals(listOf(HubStartNotice.CANT_REACH), said)
    }

    @Test
    fun `nothing is said once the hub has welcomed the phone, nor with no hub paired`() {
        notice.status(HubStatus.Unpaired)
        notice.status(HubStatus.Offline("stopped"))
        notice.status(HubStatus.Online(welcome))
        notice.status(HubStatus.Offline("closed 1006"))
        notice.status(HubStatus.Offline("x", HubFailure.Unreachable("a:1")))
        assertTrue(said.isEmpty())
    }
}
