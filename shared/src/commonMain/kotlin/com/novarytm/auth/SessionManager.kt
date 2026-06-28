package com.novarytm.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.novarytm.db.NovarytmDatabase
import com.novarytm.ffi.RustBridge
import com.novarytm.storage.SecureStorageManager
import com.novarytm.ui.GlobalExceptionHandler
import kotlinx.coroutines.*
import app.cash.sqldelight.db.SqlDriver

sealed class SessionState {
    object Onboarding : SessionState()
    object Locked : SessionState()
    data class Unlocked(val isPartner: Boolean, val justRestored: Boolean = false) : SessionState()
}

class SessionManager(
    private val driver: app.cash.sqldelight.db.SqlDriver,
    private val rustBridge: com.novarytm.ffi.RustBridge,
    private val storage: com.novarytm.storage.SecureStorageManager
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<SessionState>(
        if ((storage.getUserId() != null || storage.getTargetSyncId() != null) && storage.getPinHash() != null) {
            SessionState.Locked
        }
        else SessionState.Onboarding
    )
    val state: kotlinx.coroutines.flow.StateFlow<SessionState> = _state.asStateFlow()

    fun unlock() {
        storage.setAuthFlowActive(false)
        val isPartner = storage.getTargetSyncId() != null && storage.getUserId() == null
        _state.value = SessionState.Unlocked(isPartner)
    }

    fun lock() {
        if (storage.isAuthFlowActive()) return
        
        if (_state.value is SessionState.Unlocked) {
            rustBridge.lockDatabase()
            _state.value = SessionState.Locked
        }
    }

    fun reset() {
        storage.clearAll()
        _state.value = SessionState.Onboarding
    }
    
    private fun rekeyDatabase(masterKey: ByteArray) {
        try {
            @OptIn(ExperimentalStdlibApi::class)
            val hexKey = masterKey.toHexString()
            driver.execute(null, "PRAGMA rekey = \"x'${hexKey}'\"", 0)
        } catch (e: Exception) {
            println("Failed to rekey database: ${e.message}")
        }
    }
    
    fun completeOnboarding(userId: String, pinHash: ByteArray, masterKey: ByteArray, salt: ByteArray, isRestore: Boolean = false) {
        rekeyDatabase(masterKey)
        storage.saveUserId(userId)
        storage.savePinHash(pinHash)
        storage.saveMasterKey(masterKey)
        storage.saveDerivationSalt(salt)  // VULN-01 FIX: Persist Argon2id salt
        masterKey.fill(0)
        pinHash.fill(0)
        salt.fill(0)
        _state.value = SessionState.Unlocked(isPartner = false, justRestored = isRestore)
    }

    fun completePartnerOnboarding(targetId: String, pinHash: ByteArray, masterKey: ByteArray, secretKey: ByteArray? = null, salt: ByteArray = ByteArray(0)) {
        rekeyDatabase(masterKey)
        storage.saveTargetSyncId(targetId)
        storage.savePinHash(pinHash)
        storage.saveMasterKey(masterKey)
        secretKey?.let { 
            storage.saveSyncSecretKey(it)
            it.fill(0)
        }
        if (salt.isNotEmpty()) {
            storage.saveDerivationSalt(salt)  // VULN-01 FIX: Persist Argon2id salt
            salt.fill(0)
        }
        masterKey.fill(0)
        pinHash.fill(0)
        _state.value = SessionState.Unlocked(isPartner = true)
    }
}
