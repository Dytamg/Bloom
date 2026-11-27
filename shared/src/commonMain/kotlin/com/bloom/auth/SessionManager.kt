package com.bloom.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.bloom.ffi.RustBridge
import com.bloom.storage.SecureStorageManager
import kotlinx.coroutines.*
import app.cash.sqldelight.db.SqlDriver

sealed class SessionState {
    object Onboarding : SessionState()
    object Locked : SessionState()
    data class Unlocked(val isPartner: Boolean, val justRestored: Boolean = false) : SessionState()
}

interface SessionStore {
    fun getUserId(): String?
    fun saveUserId(id: String?)
    fun getTargetSyncId(): String?
    fun saveTargetSyncId(id: String?)
    fun getPinHash(): ByteArray?
    fun savePinHash(hash: ByteArray?)
    fun getMasterKey(): ByteArray?
    fun saveMasterKey(key: ByteArray?)
    fun isAuthFlowActive(): Boolean
    fun setAuthFlowActive(active: Boolean)
    fun clearAll()
    fun saveDerivationSalt(salt: ByteArray)
    fun saveSyncSecretKey(key: ByteArray)
}

class SessionManager(
    private val lockDatabaseAction: () -> Unit,
    private val sessionStore: SessionStore
) {
    constructor(
        rustBridge: RustBridge,
        sessionStore: SessionStore
    ) : this(
        lockDatabaseAction = { rustBridge.lockDatabase() },
        sessionStore = sessionStore
    )

    constructor(
        rustBridge: RustBridge,
        storage: SecureStorageManager
    ) : this(
        rustBridge = rustBridge,
        sessionStore = object : SessionStore {
            override fun getUserId() = storage.getUserId()
            override fun saveUserId(id: String?) { storage.saveUserId(id ?: "") }
            override fun getTargetSyncId() = storage.getTargetSyncId()
            override fun saveTargetSyncId(id: String?) { storage.saveTargetSyncId(id ?: "") }
            override fun getPinHash() = storage.getPinHash()
            override fun savePinHash(hash: ByteArray?) { hash?.let { storage.savePinHash(it) } }
            override fun getMasterKey() = storage.getMasterKey()
            override fun saveMasterKey(key: ByteArray?) { key?.let { storage.saveMasterKey(it) } }
            override fun isAuthFlowActive() = storage.isAuthFlowActive()
            override fun setAuthFlowActive(active: Boolean) { storage.setAuthFlowActive(active) }
            override fun clearAll() { storage.clearAll() }
            override fun saveDerivationSalt(salt: ByteArray) { storage.saveDerivationSalt(salt) }
            override fun saveSyncSecretKey(key: ByteArray) { storage.saveSyncSecretKey(key) }
        }
    )

    constructor(
        driver: SqlDriver,
        rustBridge: RustBridge,
        storage: SecureStorageManager
    ) : this(
        rustBridge = rustBridge,
        storage = storage
    )

    private val _state = MutableStateFlow<SessionState>(
        if ((sessionStore.getUserId() != null || !sessionStore.getTargetSyncId().isNullOrEmpty()) && sessionStore.getPinHash() != null) {
            SessionState.Locked
        }
        else SessionState.Onboarding
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun unlock() {
        sessionStore.setAuthFlowActive(false)
        val isPartner = !sessionStore.getTargetSyncId().isNullOrEmpty() && sessionStore.getUserId() == null
        _state.value = SessionState.Unlocked(isPartner)
    }

    fun lock() {
        if (sessionStore.isAuthFlowActive()) return
        
        if (_state.value is SessionState.Unlocked) {
            lockDatabaseAction()
            _state.value = SessionState.Locked
        }
    }

    fun reset() {
        lockDatabaseAction()
        sessionStore.setAuthFlowActive(false)
        sessionStore.clearAll()
        _state.value = SessionState.Onboarding
    }
    
    fun completeOnboarding(userId: String, pinHash: ByteArray, masterKey: ByteArray, salt: ByteArray, isRestore: Boolean = false) {
        try {
            if (sessionStore.getMasterKey() == null) {
                sessionStore.saveMasterKey(masterKey)
            }
            sessionStore.saveUserId(userId)
            sessionStore.savePinHash(pinHash)
            sessionStore.saveDerivationSalt(salt)
            _state.value = SessionState.Unlocked(isPartner = false, justRestored = isRestore)
        } finally {
            masterKey.fill(0)
            pinHash.fill(0)
            salt.fill(0)
        }
    }

    fun completePartnerOnboarding(targetId: String, pinHash: ByteArray, masterKey: ByteArray, secretKey: ByteArray? = null, salt: ByteArray = ByteArray(0)) {
        try {
            if (sessionStore.getMasterKey() == null) {
                sessionStore.saveMasterKey(masterKey)
            }
            sessionStore.saveTargetSyncId(targetId)
            sessionStore.savePinHash(pinHash)
            secretKey?.let { 
                sessionStore.saveSyncSecretKey(it)
            }
            if (salt.isNotEmpty()) {
                sessionStore.saveDerivationSalt(salt)
            }
            _state.value = SessionState.Unlocked(isPartner = true)
        } finally {
            masterKey.fill(0)
            pinHash.fill(0)
            secretKey?.fill(0)
            if (salt.isNotEmpty()) {
                salt.fill(0)
            }
        }
    }
}
