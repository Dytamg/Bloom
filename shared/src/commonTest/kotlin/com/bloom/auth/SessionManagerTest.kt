package com.bloom.auth

import kotlin.test.*

class SessionManagerTest {

    private class FakeSecureStorageManager : SessionStore {
        private var _userId: String? = null
        private var _targetSyncId: String? = null
        private var _pinHash: ByteArray? = null
        private var _masterKey: ByteArray? = null
        private var _authFlowActive = false
        var cleared = false

        override fun getUserId(): String? = _userId
        override fun saveUserId(id: String?) { _userId = id }
        override fun getTargetSyncId(): String? = _targetSyncId
        override fun saveTargetSyncId(id: String?) { _targetSyncId = id }
        override fun getPinHash(): ByteArray? = _pinHash
        override fun savePinHash(hash: ByteArray?) { _pinHash = hash }
        override fun getMasterKey(): ByteArray? = _masterKey
        override fun saveMasterKey(key: ByteArray?) { _masterKey = key }
        override fun isAuthFlowActive(): Boolean = _authFlowActive
        override fun setAuthFlowActive(active: Boolean) { _authFlowActive = active }
        override fun clearAll() { cleared = true; _userId = null; _targetSyncId = null; _pinHash = null; _masterKey = null }
        override fun saveDerivationSalt(salt: ByteArray) {}
        override fun saveSyncSecretKey(key: ByteArray) {}
    }

    @Test
    fun testInitialStateLockedWhenPinAndUserIdExist() {
        val storage = FakeSecureStorageManager().apply {
            saveUserId("user_123")
            savePinHash(ByteArray(32) { 1 })
        }
        var lockCalled = false

        val sessionManager = SessionManager(
            lockDatabaseAction = { lockCalled = true },
            sessionStore = storage
        )
        assertEquals(SessionState.Locked, sessionManager.state.value)
    }

    @Test
    fun testInitialStateOnboardingWhenNoPin() {
        val storage = FakeSecureStorageManager()
        var lockCalled = false

        val sessionManager = SessionManager(
            lockDatabaseAction = { lockCalled = true },
            sessionStore = storage
        )
        assertEquals(SessionState.Onboarding, sessionManager.state.value)
    }

    @Test
    fun testUnlockStateTransition() {
        val storage = FakeSecureStorageManager().apply {
            saveUserId("user_123")
            savePinHash(ByteArray(32) { 1 })
            setAuthFlowActive(false)
        }
        var lockCalled = false

        val sessionManager = SessionManager(
            lockDatabaseAction = { lockCalled = true },
            sessionStore = storage
        )
        sessionManager.unlock()

        assertTrue(sessionManager.state.value is SessionState.Unlocked)
    }

    @Test
    fun testResetFromPartnerSessionLocksDbClearsAuthFlowAndReturnsToOnboarding() {
        val storage = FakeSecureStorageManager().apply {
            saveTargetSyncId("saf_local_file")
            savePinHash(ByteArray(32) { 2 })
            setAuthFlowActive(true)
        }
        var lockCount = 0
        val sessionManager = SessionManager(
            lockDatabaseAction = { lockCount++ },
            sessionStore = storage
        )
        sessionManager.unlock()
        assertEquals(SessionState.Unlocked(isPartner = true), sessionManager.state.value)

        storage.setAuthFlowActive(true)
        sessionManager.reset()

        assertEquals(SessionState.Onboarding, sessionManager.state.value)
        assertTrue(storage.cleared, "reset() must clear persisted credentials")
        assertFalse(storage.isAuthFlowActive(), "reset() must clear authFlowActive")
        assertEquals(1, lockCount, "reset() must lock the native database")
    }

    @Test
    fun testResetOnboardingStageClearsBiometricsCredentialsAndReturnsWelcome() {
        val hash = byteArrayOf(1, 2, 3)
        val masterKey = byteArrayOf(4, 5, 6)
        val salt = byteArrayOf(7, 8, 9)
        val secretKey = byteArrayOf(10, 11, 12)
        val staleBiometrics = com.bloom.ui.OnboardingStage.BiometricsSetup(
            userId = "partner-target",
            hash = hash,
            masterKey = masterKey,
            salt = salt,
            isPartner = true,
            isRestore = false,
            secretKey = secretKey
        )

        val resetStage = com.bloom.ui.resetOnboardingStage(staleBiometrics)

        assertTrue(resetStage is com.bloom.ui.OnboardingStage.Welcome)
        assertTrue(hash.all { it == 0.toByte() })
        assertTrue(masterKey.all { it == 0.toByte() })
        assertTrue(salt.all { it == 0.toByte() })
        assertTrue(secretKey.all { it == 0.toByte() })
    }
}
