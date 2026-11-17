package com.lite404.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WdttRoutesTest {
    @Test fun defaultRouteIsUnchanged() {
        assertEquals(listOf("0.0.0.0/0"), WdttRoutes.build(false, "", "", emptyList()))
    }

    @Test fun prefixBypassKeepsTheMoreSpecificForcedRoute() {
        assertEquals(
            listOf("64.0.0.0/2", "128.0.0.0/1"),
            WdttRoutes.build(false, "0.0.0.0/1", "64.0.0.0/2", emptyList())
        )
    }

    @Test fun tunnelDnsSurvivesExclusion() {
        assertEquals(listOf("10.66.0.1/32"),
            WdttRoutes.build(true, "0.0.0.0/0", "", listOf("10.66.0.1/32")))
    }

    @Test fun invalidInputsAreReportedAndDoNotModifyRoutes() {
        val input = "example.org\n256.0.0.0/32\n192.0.2.0/33 # invalid"
        assertEquals(listOf("example.org", "256.0.0.0/32", "192.0.2.0/33"), WdttRoutes.invalidEntries(input))
        assertEquals(listOf("0.0.0.0/0"), WdttRoutes.build(false, input, "", emptyList()))
        assertTrue(WdttRoutes.invalidEntries("192.0.2.5/27 # host bits").isEmpty())
        assertFalse(WdttRoutes.build(false, "192.0.2.0/24", "", emptyList()).isEmpty())
    }
}
