package com.example.data.repository

import com.example.BuildConfig
import com.example.data.api.Content
import com.example.data.api.GenerateContentRequest
import com.example.data.api.Part
import com.example.data.api.RetrofitClient
import com.example.data.api.GenerationConfig
import com.example.data.db.ChatDao
import com.example.data.db.ChatMessage
import com.example.data.db.ChatSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import android.util.Log

class ChatRepository(private val chatDao: ChatDao) {

    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessions()

    fun getSessionMessages(sessionId: Int): Flow<List<ChatMessage>> {
        return chatDao.getSessionMessages(sessionId)
    }

    suspend fun createNewSession(age: Int? = null, gender: String? = null): Int = withContext(Dispatchers.IO) {
        val session = ChatSession(
            title = "New Symptom Analysis",
            age = age,
            gender = gender
        )
        val sessionId = chatDao.insertSession(session).toInt()
        
        // Insert automated welcoming message from AI
        val welcomeMsg = "Hello! I am your clinical Symptom AI Assistant. 🩺\n\n" +
                "I can analyze your symptoms to provide potential conditions, urgency level, and self-care recommendations.\n\n" +
                "To get started, please **describe your symptoms** in as much detail as possible (e.g. what hurts, when did it start, how severe is it on a scale of 1-10, any other related symptoms?)."
        
        chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                sender = "ai",
                content = welcomeMsg
            )
        )
        
        sessionId
    }

    suspend fun addMessage(sessionId: Int, sender: String, content: String) = withContext(Dispatchers.IO) {
        chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                sender = sender,
                content = content
            )
        )
    }

    suspend fun deleteSession(sessionId: Int) = withContext(Dispatchers.IO) {
        chatDao.deleteSessionById(sessionId)
    }

    suspend fun generateSymptomReport(sessionId: Int): String? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey == "MY_GEMINI_API_KEY" || apiKey.isEmpty()) {
            return@withContext "API Configuration Error: Please configure your Gemini API Key in the Secrets panel."
        }

        val messages = chatDao.getSessionMessages(sessionId).first()
        val session = chatDao.getSessionById(sessionId) ?: return@withContext null

        // Format history for Gemini
        // We exclude the first welcoming message if it was automated, or keep it.
        // Direct REST API contents format:
        val contentsList = messages.map { msg ->
            val role = if (msg.sender == "user") "user" else "model"
            Content(
                parts = listOf(Part(text = msg.content)),
                role = role
            )
        }

        val promptAppendix = "\n\n[INSTRUCTION: Please compile a complete, beautifully structured clinical-grade report of my symptoms, containing potential causes, a risk/urgency categorization (LOW, MEDIUM, HIGH, EMERGENCY), recommended steps, and red flag indicators. Present it professionally.]"

        val updatedContents = contentsList.toMutableList().apply {
            add(Content(parts = listOf(Part(text = promptAppendix)), role = "user"))
        }

        val request = GenerateContentRequest(
            contents = updatedContents,
            systemInstruction = Content(parts = listOf(Part(text = MEDICAL_SYSTEM_INSTRUCTION))),
            generationConfig = GenerationConfig(temperature = 0.2f) // keep it deterministic and formal
        )

        try {
            val response = RetrofitClient.apiService.generateContent(apiKey, request)
            val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!responseText.isNullOrEmpty()) {
                // Update session report
                val severity = parseSeverity(responseText)
                chatDao.updateSession(
                    session.copy(
                        finalReport = responseText,
                        severity = severity
                    )
                )
                responseText
            } else {
                "No clinical report returned by the model."
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error compiling report", e)
            "Error rendering symptom report: ${e.message}"
        }
    }

    suspend fun getAiResponseAndSave(sessionId: Int, userMessage: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey == "MY_GEMINI_API_KEY" || apiKey.isEmpty()) {
            val errorMsg = "API Key error: Please configure your GEMINI_API_KEY inside the Secrets panel of Google AI Studio."
            chatDao.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    sender = "ai",
                    content = errorMsg
                )
            )
            return@withContext errorMsg
        }

        // 1. Save user message to database
        chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                sender = "user",
                content = userMessage
            )
        )

        // 2. Load all messages in this session
        val chatHistory = chatDao.getSessionMessages(sessionId).first()
        val session = chatDao.getSessionById(sessionId)

        // Build Gemini conversational flow
        // Mapping 'user' sender -> 'user' role, 'ai' sender -> 'model' role
        val contentsList = chatHistory.map { msg ->
            val role = if (msg.sender == "user") "user" else "model"
            Content(
                parts = listOf(Part(text = msg.content)),
                role = role
            )
        }

        val request = GenerateContentRequest(
            contents = contentsList,
            systemInstruction = Content(parts = listOf(Part(text = MEDICAL_SYSTEM_INSTRUCTION))),
            generationConfig = GenerationConfig(temperature = 0.5f)
        )

        try {
            val response = RetrofitClient.apiService.generateContent(apiKey, request)
            val aiResponseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                ?: "I apologize, I experienced a problem analyzing your request. Please try rephrasing your symptoms."

            // 3. Save AI message to database
            chatDao.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    sender = "ai",
                    content = aiResponseText
                )
            )

            // 4. Background tasks: Dynamic Session Renaming
            // If session title is still the default, let's ask Gemini to give it a 2-4 word clinical name!
            if (session != null && (session.title == "New Symptom Analysis" || session.title.startsWith("New Session"))) {
                renameSessionDynamically(sessionId, userMessage)
            }

            aiResponseText
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error during chat generation", e)
            val errorMsg = "Network error: ${e.localizedMessage ?: "Please check your internet connection and try again."}"
            chatDao.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    sender = "ai",
                    content = errorMsg
                )
            )
            errorMsg
        }
    }

    private suspend fun renameSessionDynamically(sessionId: Int, lastUserMessage: String) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey == "MY_GEMINI_API_KEY" || apiKey.isEmpty()) return

        val renamePrompt = "Create a brief summary (exactly 2 to 4 words) of the symptoms described. Do not use quotes or punctuation. Example: 'Fever and Sore Throat'. Prompt: '$lastUserMessage'"
        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = renamePrompt)))),
            generationConfig = GenerationConfig(temperature = 0.3f, maxOutputTokens = 15)
        )

        try {
            val response = RetrofitClient.apiService.generateContent(apiKey, request)
            val newTitle = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
            if (!newTitle.isNullOrEmpty()) {
                val session = chatDao.getSessionById(sessionId)
                if (session != null) {
                    chatDao.updateSession(session.copy(title = newTitle.replace("\"", "")))
                }
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error renaming session", e)
        }
    }

    private fun parseSeverity(aiResponse: String): String {
        val uppercaseResponse = aiResponse.uppercase()
        return when {
            uppercaseResponse.contains("EMERGENCY") || uppercaseResponse.contains("RED FLAG") -> "Emergency"
            uppercaseResponse.contains("HIGH RISK") || uppercaseResponse.contains("HIGH URGENCY") -> "High"
            uppercaseResponse.contains("MEDIUM RISK") || uppercaseResponse.contains("MEDIUM URGENCY") -> "Medium"
            else -> "Low"
        }
    }

    companion object {
        private val MEDICAL_SYSTEM_INSTRUCTION = """
            You are "Symptom AI", a professional medical symptom analyzer assistant.
            The user is conversing with you about their health symptoms. Your goal is to guide them, evaluate their symptoms step-by-step, and deliver a comprehensive diagnostic triaging.
            
            RULES OF ENGAGEMENT:
            1. DISCLAIMER FIRST: You must begin or integrate a clear, prominent warning: "Disclaimer: I am an AI, not a doctor. This content is for informational purposes only and does not substitute for professional medical care."
            2. CONVERSATIONAL CLINIC: Act like a caring, detail-oriented examiner. Ask clarifying questions (e.g., duration, fever level, specific areas of pain) to gather exact insights. Don't ask more than 2 questions at a time to prevent overwhelming the user.
            3. ANALYSIS & SUGGESTIONS: Mention 2-4 possible, non-definitive conditions ("Possible considerations include...", "Could be associated with...").
            4. ALERTNESS ON RED FLAGS: If symptoms relate to chest pain, sudden numbness, severe breathing difficulty, massive bleeding, or high trauma, immediately issue an EMERGENCY warning urging them to call 911 or visit the ER.
            5. RISK CATEGORIZATION: Mention the urgency level clearly. Classify the user situation into:
               - EMERGENCY: Seek immediate urgent care/911.
               - HIGH URGENCY: Consult a doctor within 24 hours.
               - MEDIUM URGENCY: Schedule a routine clinic visit.
               - LOW RISK: Home remedies, rest, OTC medication, monitor symptoms.
            6. FORMATTING: Use clean Markdown structure with bullet points, icons, bold headers, and line breaks so that it renders beautifully inside the mobile app list cards.
        """.trimIndent()
    }
}
