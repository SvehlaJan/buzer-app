package app.buzer.android.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeviceIdentityStoreTest {
    @Test
    fun installationIdIsGeneratedOnceAndSurvivesStoreReconstruction() {
        val storage = FakeDeviceIdentityStorage()

        val first = DeviceIdentityStore(storage)
        val firstId = first.deviceId()
        val reconstructed = DeviceIdentityStore(storage)

        assertEquals(firstId, reconstructed.deviceId())
        assertEquals(1, storage.writes)
    }

    @Test
    fun everyNewUserIntentGetsFreshActionIdWhileRetryUsesCallerHeldId() {
        val store = DeviceIdentityStore(FakeDeviceIdentityStorage())

        val firstIntent = store.newActionId()
        val retryAction = firstIntent
        val secondIntent = store.newActionId()

        assertEquals(firstIntent, retryAction)
        assertNotEquals(firstIntent, secondIntent)
    }

    private class FakeDeviceIdentityStorage : DeviceIdentityStorage {
        private var value: String? = null
        var writes = 0

        override fun readDeviceId(): String? = value

        override fun writeDeviceId(deviceId: String) {
            value = deviceId
            writes += 1
        }
    }
}
