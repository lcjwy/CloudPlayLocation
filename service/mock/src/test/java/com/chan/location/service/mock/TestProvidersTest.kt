package com.chan.location.service.mock

import android.location.LocationManager
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TestProvidersTest {
    @Test
    fun partialRegistrationFailureRollsBackBothProviders() {
        val manager = mock(LocationManager::class.java)
        doThrow(SecurityException("network registration denied"))
            .`when`(manager)
            .addTestProvider(
                eq(LocationManager.NETWORK_PROVIDER),
                anyBoolean(),
                anyBoolean(),
                anyBoolean(),
                anyBoolean(),
                anyBoolean(),
                anyBoolean(),
                anyBoolean(),
                anyInt(),
                anyInt(),
            )

        try {
            TestProviders(manager).addAll()
            fail("Registration must report failure")
        } catch (expected: SecurityException) {
            // Initial cleanup plus rollback after GPS succeeded and Network failed.
            verify(manager, times(2)).removeTestProvider(LocationManager.GPS_PROVIDER)
            verify(manager, times(2)).removeTestProvider(LocationManager.NETWORK_PROVIDER)
        }
    }

    @Test
    fun successfulRegistrationKeepsBothProviders() {
        val manager = mock(LocationManager::class.java)
        `when`(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(true)
        `when`(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)).thenReturn(true)

        assertTrue(TestProviders(manager).addAll())
        verify(manager).removeTestProvider(LocationManager.GPS_PROVIDER)
        verify(manager).removeTestProvider(LocationManager.NETWORK_PROVIDER)
    }
}
