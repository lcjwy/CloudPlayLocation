package com.chan.location.core.common

import android.content.Context
import android.location.LocationManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class MockLocationAccessTest {
    @Test
    fun permissionProbeDoesNotRemoveActiveProviders() {
        val context = mock(Context::class.java)
        val manager = mock(LocationManager::class.java)
        `when`(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(manager)

        assertTrue(MockLocationAccess.isGranted(context))

        verify(manager).removeTestProvider("com.chan.location.permission_probe")
        verify(manager, never()).removeTestProvider(LocationManager.GPS_PROVIDER)
        verify(manager, never()).removeTestProvider(LocationManager.NETWORK_PROVIDER)
        verify(manager).addTestProvider(
            eq("com.chan.location.permission_probe"),
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
    }

    @Test
    fun cleanupFailureDoesNotChangePermissionResult() {
        val context = mock(Context::class.java)
        val manager = mock(LocationManager::class.java)
        `when`(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(manager)
        doThrow(SecurityException("cleanup denied"))
            .`when`(manager)
            .setTestProviderEnabled(anyString(), anyBoolean())

        assertTrue(MockLocationAccess.isGranted(context))
        verify(manager).removeTestProvider("com.chan.location.permission_probe")
    }
}
