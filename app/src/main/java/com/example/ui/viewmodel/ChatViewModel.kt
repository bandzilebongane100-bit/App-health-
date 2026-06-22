package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.ChatMessage
import com.example.data.db.ChatSession
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = ChatRepository(db.chatDao())

    // All historic chat sessions
    val sessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _activeSessionId = MutableStateFlow<Int?>(null)
    val activeSessionId: StateFlow<Int?> = _activeSessionId.asStateFlow()

    // Screen navigation flow: "dashboard" or "chat"
    private val _currentScreen = MutableStateFlow<String>("dashboard")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    // Messages for the active session
    val activeMessages: StateFlow<List<ChatMessage>> = _activeSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getSessionMessages(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _activeSession = MutableStateFlow<ChatSession?>(null)
    val activeSession: StateFlow<ChatSession?> = _activeSession.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isCompilingReport = MutableStateFlow(false)
    val isCompilingReport: StateFlow<Boolean> = _isCompilingReport.asStateFlow()

    init {
        viewModelScope.launch {
            _activeSessionId.collect { id ->
                if (id != null) {
                    val sessionList = sessions.value
                    _activeSession.value = sessionList.find { it.id == id }
                } else {
                    _activeSession.value = null
                }
            }
        }
        viewModelScope.launch {
            // Synchronize active session if list updates
            sessions.collect { sessionList ->
                val currentId = _activeSessionId.value
                if (currentId != null) {
                    _activeSession.value = sessionList.find { it.id == currentId }
                }
            }
        }
    }

    fun navigateToDashboard() {
        _activeSessionId.value = null
        _currentScreen.value = "dashboard"
    }

    fun startNewSession(age: Int? = null, gender: String? = null) {
        viewModelScope.launch {
            _isGenerating.value = true
            val sessionId = repository.createNewSession(age, gender)
            _activeSessionId.value = sessionId
            _currentScreen.value = "chat"
            _isGenerating.value = false
        }
    }

    fun selectSession(sessionId: Int) {
        _activeSessionId.value = sessionId
        _currentScreen.value = "chat"
    }

    fun sendMessage(text: String) {
        val sessionId = _activeSessionId.value ?: return
        if (text.isBlank()) return

        viewModelScope.launch {
            _isGenerating.value = true
            repository.getAiResponseAndSave(sessionId, text)
            _isGenerating.value = false
        }
    }

    fun deleteSession(sessionId: Int) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_activeSessionId.value == sessionId) {
                navigateToDashboard()
            }
        }
    }

    fun compileReport() {
        val sessionId = _activeSessionId.value ?: return
        viewModelScope.launch {
            _isCompilingReport.value = true
            repository.generateSymptomReport(sessionId)
            _isCompilingReport.value = false
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                return ChatViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
