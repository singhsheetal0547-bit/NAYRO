package com.example

import com.advanceagent.core.data.NayroPersonalityMode
import com.advanceagent.core.data.PromptGenerator
import com.advanceagent.core.data.SecurePreferences
import com.advanceagent.core.data.chat.ChatRepository
import com.advanceagent.core.network.BidiSetupConfig
import com.advanceagent.core.network.BidiSetupMessage
import com.advanceagent.core.network.GeminiServerMessage
import com.advanceagent.core.tools.AgentToolDeclarations
import com.advanceagent.core.tools.AgentToolRouter
import com.advanceagent.core.tools.ContactResolver
import com.advanceagent.core.tools.DomainNormalizer
import com.advanceagent.core.tools.ResolvedContactCandidate
import com.advanceagent.core.tools.WhatsAppAssistant
import com.advanceagent.core.tools.YouTubeApiClient
import com.advanceagent.core.vision.UiElementInfo
import com.advanceagent.core.vision.VisualTaskOrchestrator
import com.advanceagent.core.wakeword.SherpaWakeWordEngine
import com.advanceagent.core.wakeword.WakeWordTokenizer
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvanceVoiceAgentTests {

    @Test
    fun testWakeWordTokenizerBuildsDualKeywordsPayloadWithBabuAndCustomWords() {
        val prefs = SecurePreferences()
        prefs.appWakeWordEnabled = true
        prefs.appWakeWord = "HI BABU, HEY BABU, HI NAYRO"
        prefs.userWakeWordEnabled = true
        prefs.userCustomWakeWordsRaw = "HEY ARJUN, WAKE UP BOSS"

        val activeWords = prefs.getAllActiveWakeWords()
        assertEquals(
            listOf("HI BABU", "HEY BABU", "HI NAYRO", "HEY ARJUN", "WAKE UP BOSS"),
            activeWords
        )

        val tokenizer = WakeWordTokenizer(WakeWordTokenizer.DEFAULT_FALLBACK_VOCAB)
        val spec = tokenizer.buildKeywordsSpec(
            phrases = activeWords,
            boostingScore = 1.8f,
            triggerThreshold = 0.18f
        )

        val lines = spec.lines().filter { it.isNotBlank() }
        assertEquals(5, lines.size)
        assertTrue(lines[0].contains("@HI_BABU"))
        assertTrue(lines[0].contains(":1.8 #0.18"))
        assertTrue(lines[1].contains("@HEY_BABU"))
        assertTrue(lines[2].contains("@HI_NAYRO"))
        assertTrue(lines[3].contains("@HEY_ARJUN"))
        assertTrue(lines[4].contains("@WAKE_UP_BOSS"))
    }

    @Test
    fun testSherpaWakeWordEngineConfigCooldownAndHonestHeadlessJvmBehavior() {
        var fakeNow = 10_000L
        val engine = SherpaWakeWordEngine(
            context = null,
            cooldownMs = 1800L
        )
        engine.currentTimeProvider = { fakeNow }

        val config = engine.buildKeywordSpotterConfig(
            boostingScore = 2.0f,
            triggerThreshold = 0.25f
        )
        assertEquals(16000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(
            "kws-model/${SherpaWakeWordEngine.ENCODER_ASSET_NAME}",
            config.modelConfig.transducer.encoder
        )
        assertEquals(
            "kws-model/${SherpaWakeWordEngine.DECODER_ASSET_NAME}",
            config.modelConfig.transducer.decoder
        )
        assertEquals(
            "kws-model/${SherpaWakeWordEngine.JOINER_ASSET_NAME}",
            config.modelConfig.transducer.joiner
        )
        assertEquals(
            "kws-model/${SherpaWakeWordEngine.TOKENS_ASSET_NAME}",
            config.modelConfig.tokens
        )
        assertEquals(
            "kws-model/${SherpaWakeWordEngine.KEYWORDS_ASSET_NAME}",
            config.keywordsFile
        )

        // Cooldown gate enforces cooldownMs strictly
        assertTrue(engine.shouldAllowTrigger())
        fakeNow += 500L
        assertFalse(engine.shouldAllowTrigger())
        fakeNow += 1500L
        assertTrue(engine.shouldAllowTrigger())

        // Without an Android Context / AssetManager, native Sherpa-ONNX JNI honestly reports false
        assertFalse(
            engine.initialize(
                activeWakeWords = listOf("HI BABU", "HEY BABU", "HI NAYRO"),
                boostingScore = 2.0f,
                triggerThreshold = 0.25f
            )
        )
        assertFalse(engine.isNativeRuntimeActive)
        assertNotNull(engine.lastInitError)
        assertTrue(engine.lastInitError!!.isNotBlank())

        val silentFrame = ShortArray(320) { 0 }
        assertNull(engine.processPcm16(silentFrame, silentFrame.size))
        engine.release()
    }

    @Test
    fun testPromptGeneratorInjectsBabuIdentityPersonalityModeAndMemories() {
        val promptFocus = PromptGenerator.buildGeminiLiveSystemInstruction(
            assistantName = "Babu",
            userName = "Boss",
            personalityMode = NayroPersonalityMode.FOCUS,
            memories = listOf("Prefers Hinglish", "Works on Android Kotlin", "prefers hinglish")
        )
        assertTrue(promptFocus.contains("You are Babu (powered by NAYRO)"))
        assertTrue(promptFocus.contains("Address the user respectfully as \"Boss\""))
        assertTrue(promptFocus.contains("Focus Mode"))
        assertTrue(promptFocus.contains("- Prefers Hinglish"))
        assertTrue(promptFocus.contains("- Works on Android Kotlin"))
        // Verify case-insensitive deduplication in PromptGenerator
        assertEquals(1, Regex("- Prefers Hinglish", RegexOption.IGNORE_CASE).findAll(promptFocus).count())
    }

    @Test
    fun testDomainNormalizerNormalizesSpokenDomainsAndSearchQueries() {
        assertEquals(
            "https://youtube.com",
            DomainNormalizer.normalizeSpokenTargetToUrl("open youtube dot com")
        )
        assertEquals(
            "https://github.com/android",
            DomainNormalizer.normalizeSpokenTargetToUrl("go to github dot com slash android")
        )
        assertEquals(
            "https://google.co.in",
            DomainNormalizer.normalizeSpokenTargetToUrl("visit google dot co dot in")
        )
        val searchUrl = DomainNormalizer.normalizeSpokenTargetToUrl("best masala chai recipe")
        assertTrue(searchUrl.startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun testWhatsAppAssistantDisambiguatesAndRequiresConfirmationBeforeSending() {
        val resolver = ContactResolver(context = null)
        resolver.seedTestContacts(
            listOf(
                ResolvedContactCandidate("Rahul Sharma", "+91 98765 43210", "919876543210"),
                ResolvedContactCandidate("Rahul Verma", "+91 91234 56789", "919123456789"),
                ResolvedContactCandidate("Priya Nair", "+91 99887 76655", "919988776655")
            )
        )
        val assistant = WhatsAppAssistant(context = null, contactResolver = resolver)

        // 1. Ambiguous contact name "Rahul" -> returns disambiguation_required, verified_sent = false
        val ambiguous = assistant.stageOrExecuteWhatsApp(
            target = "Rahul",
            message = "Are we meeting at 5?",
            requireConfirmation = true
        )
        assertEquals("disambiguation_required", ambiguous["status"])
        assertEquals(false, ambiguous["verified_sent"])

        // 2. Specific contact "Priya Nair" with requireConfirmation = true -> stages draft, awaits confirmation
        val staged = assistant.stageOrExecuteWhatsApp(
            target = "Priya Nair",
            message = "Reached office safely",
            requireConfirmation = true,
            confirmedByUser = false
        )
        assertEquals("confirmation_required", staged["status"])
        assertEquals(false, staged["verified_sent"])
        assertNotNull(assistant.pendingDraft)
        val draftId = staged["draft_id"]?.toString()
        assertNotNull(draftId)

        // 3. Confirming draft in headless environment never falsely claims verified_sent = true
        val confirmed = assistant.confirmAndSendPendingDraft(draftId)
        assertEquals("staged_for_device", confirmed["status"])
        assertEquals(false, confirmed["verified_sent"])
        assertNull(assistant.pendingDraft)
    }

    @Test
    fun testMemoryDeduplicationSensitiveGuardAndModelIdValidation() = runBlocking {
        val router = AgentToolRouter(context = null, memoryDb = null)
        val first = router.saveMemoryDeduplicated("User likes dark mode")
        assertEquals("ok", first["status"])

        val duplicate = router.saveMemoryDeduplicated("user likes dark mode")
        assertEquals("duplicate_skipped", duplicate["status"])

        val sensitive = router.saveMemoryDeduplicated("My key is AIzaSySecretKey12345")
        assertEquals("rejected_sensitive", sensitive["status"])

        assertTrue(SecurePreferences.validateGeminiLiveModelId("models/gemini-2.0-flash-live-001").first)
        assertTrue(SecurePreferences.validateGeminiLiveModelId("gemini-2.5-flash-native-audio-preview-09-2025").first)
        assertFalse(SecurePreferences.validateGeminiLiveModelId("invalid-non-gemini-model").first)
    }

    @Test
    fun testYouTubeApiClientParsesSearchItems() {
        val sampleJson = """
            {
              "items": [
                {
                  "id": { "videoId": "dQw4w9WgXcQ" },
                  "snippet": {
                    "title": "Arijit Singh Live Acoustic",
                    "channelTitle": "Sony Music India"
                  }
                }
              ]
            }
        """.trimIndent()
        val client = YouTubeApiClient()
        val videos = client.parseSearchItems(sampleJson)
        assertEquals(1, videos.size)
        assertEquals("dQw4w9WgXcQ", videos[0].videoId)
        assertEquals("Arijit Singh Live Acoustic", videos[0].title)
        assertEquals("Sony Music India", videos[0].channelTitle)
    }

    @Test
    fun testVisualTaskOrchestratorComputesDistinctScreenSignatures() {
        val orchestrator = VisualTaskOrchestrator(context = null)
        val nodesA = listOf(
            UiElementInfo("Send", "Send Button", "com.whatsapp:id/send", "ImageButton", true, false, false, 860, 1760, 940, 1840)
        )
        val nodesB = listOf(
            UiElementInfo("Delivered", "", "com.whatsapp:id/status", "TextView", false, false, false, 400, 1180, 600, 1220)
        )
        val sigA = orchestrator.computeScreenSignature(nodesA)
        val sigB = orchestrator.computeScreenSignature(nodesB)
        assertFalse(sigA == sigB)
    }

    @Test
    fun testGsonCompatSerializesAndDeserializesGeminiLivePayloadsAndTranscriptions() {
        val gson = Gson()
        val tools = AgentToolDeclarations.allTools(includeGoogleSearchGrounding = true)
        assertEquals(2, tools.size)
        val declarations = tools.first().functionDeclarations
        assertNotNull(declarations)
        assertEquals(14, declarations!!.size)
        assertNotNull(tools[1].googleSearch)

        val setupMsg = BidiSetupMessage(
            setup = BidiSetupConfig(
                model = "models/gemini-2.0-flash-live-001",
                tools = tools,
                inputAudioTranscription = emptyMap(),
                outputAudioTranscription = emptyMap()
            )
        )
        val json = gson.toJson(setupMsg)
        assertTrue(json.contains("\"functionDeclarations\""))
        assertTrue(json.contains("\"googleSearch\""))
        assertTrue(json.contains("\"confirm_whatsapp_draft\""))
        assertTrue(json.contains("\"control_youtube_playback\""))
        assertTrue(json.contains("\"open_website\""))

        val serverJson = """
            {
              "serverContent": {
                "interrupted": false,
                "turnComplete": true,
                "inputTranscription": { "text": "Hi Babu play Arijit Singh on YouTube" },
                "outputTranscription": { "text": "Sure Boss, playing Arijit Singh on YouTube now." },
                "modelTurn": {
                  "parts": [
                    {
                      "inlineData": {
                        "mimeType": "audio/pcm;rate=24000",
                        "data": "AAECAw=="
                      }
                    }
                  ]
                }
              },
              "toolCall": {
                "functionCalls": [
                  {
                    "id": "call_99",
                    "name": "search_youtube",
                    "args": {
                      "query": "Arijit Singh"
                    }
                  }
                ]
              },
              "toolCallCancellation": {
                "ids": ["call_cancelled_1"]
              },
              "sessionResumptionUpdate": {
                "newHandle": "resume_token_xyz",
                "resumable": true
              },
              "goAway": {
                "timeLeft": "30s"
              }
            }
        """.trimIndent()

        val parsed = gson.fromJson(serverJson, GeminiServerMessage::class.java)
        assertNotNull(parsed)
        assertEquals(true, parsed.serverContent?.turnComplete)
        assertEquals(
            "Hi Babu play Arijit Singh on YouTube",
            parsed.serverContent?.inputTranscription?.text
        )
        assertEquals(
            "Sure Boss, playing Arijit Singh on YouTube now.",
            parsed.serverContent?.outputTranscription?.text
        )
        assertEquals("audio/pcm;rate=24000", parsed.serverContent?.modelTurn?.parts?.firstOrNull()?.inlineData?.mimeType)
        assertEquals("search_youtube", parsed.toolCall?.functionCalls?.firstOrNull()?.name)
        assertEquals("Arijit Singh", parsed.toolCall?.functionCalls?.firstOrNull()?.args?.get("query")?.asString)
        assertEquals(listOf("call_cancelled_1"), parsed.toolCallCancellation?.ids)
        assertEquals("resume_token_xyz", parsed.sessionResumptionUpdate?.newHandle)
        assertEquals("30s", parsed.goAway?.timeLeft)

        ChatRepository.clearAll()
        ChatRepository.recordUserTranscript("Hi Babu")
        ChatRepository.recordAssistantTranscript("Namaste Boss!")
        ChatRepository.recordToolExecution("search_youtube", "Playing Arijit Singh", isVerified = true)
        assertEquals(3, ChatRepository.turns.value.size)
    }

    @Test
    fun testWakeWordEngineLifecycleAndBpeTokenization() {
        val engine = com.advanceagent.core.wakeword.WakeWordEngine(context = null)
        // Without Android Context/AssetManager, native Sherpa-ONNX JNI cannot load -> honest ERROR state
        assertFalse(engine.initialize(listOf("HI BABU", "HEY BABU", "HI NAYRO")))
        assertEquals(
            com.advanceagent.core.wakeword.WakeWordEngine.LifecycleState.ERROR,
            engine.state.value
        )

        val spec = engine.tokenizeWakePhrases(listOf("HI BABU", "HI NAYRO"))
        assertTrue(spec.contains("@HI_BABU"))
        assertTrue(spec.contains("@HI_NAYRO"))

        assertTrue(engine.startForegroundMicrophoneService())
        assertEquals(
            com.advanceagent.core.wakeword.WakeWordEngine.LifecycleState.FOREGROUND_LISTENING,
            engine.state.value
        )

        engine.stopForegroundMicrophoneService()
        assertEquals(
            com.advanceagent.core.wakeword.WakeWordEngine.LifecycleState.STOPPED,
            engine.state.value
        )
    }

    @Test
    fun testDekhoVisionCommandOrchestrationAndClassification() {
        assertTrue(VisualTaskOrchestrator.isDekhoVisionCommand("Babu dekho"))
        assertTrue(VisualTaskOrchestrator.isDekhoVisionCommand("Screen dekho"))
        assertTrue(VisualTaskOrchestrator.isDekhoVisionCommand("देखो"))

        val classified = com.example.core.agent.TaskClassifier.classify("Babu, dekho")
        assertEquals(com.example.core.agent.UserIntentType.SCREEN_READ, classified.type)

        val orchestrator = VisualTaskOrchestrator(context = null)
        val mockElements = listOf(
            UiElementInfo("Play", "Play Video", "com.google.android.youtube:id/play", "ImageView", true, false, false, 400, 800, 600, 1000)
        )
        val dekhoResult = orchestrator.executeDekhoVisionCommand(
            userPrompt = "Babu dekho",
            preCapturedElements = mockElements,
            preCapturedJpegBase64 = "fakeJpegBase64Payload=="
        )
        assertEquals("ok", dekhoResult["status"])
        assertEquals("DEKHO", dekhoResult["command"])
        assertEquals(true, dekhoResult["has_jpeg_snapshot"])
        assertEquals(1, dekhoResult["element_count"])
        assertTrue(dekhoResult["summary"].toString().contains("Dekho Vision Grounded"))
    }

    @Test
    fun testGeminiLiveServiceAudioStreamingAndBargeInInterruption() {
        val liveService = com.advanceagent.core.network.GeminiLiveService(context = null)
        assertEquals(16000, com.advanceagent.core.network.GeminiLiveService.INPUT_SAMPLE_RATE_HZ)
        assertEquals(24000, com.advanceagent.core.network.GeminiLiveService.OUTPUT_SAMPLE_RATE_HZ)

        // 1. Stream 16kHz PCM input chunk
        val pcm16k = ByteArray(640) { 1 }
        val inputMsg = liveService.stream16kHzPcmInput(pcm16k)
        assertEquals("audio/pcm;rate=16000", inputMsg.realtimeInput.mediaChunks.first().mimeType)

        // 2. Receive 24kHz PCM output from Gemini Live server
        val serverAudioJson = """
            {
              "serverContent": {
                "interrupted": false,
                "turnComplete": false,
                "modelTurn": {
                  "parts": [
                    {
                      "inlineData": {
                        "mimeType": "audio/pcm;rate=24000",
                        "data": "AAECAwQFBgc="
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        liveService.processServerMessageJson(serverAudioJson)
        assertEquals("SPEAKING", liveService.sessionState.value)
        assertFalse(liveService.isBargeInInterrupted.value)
        assertEquals(24000, liveService.lastAudioOutputSampleRate.value)

        // 3. Receive natural barge-in interruption via serverContent.interrupted = true
        val interruptedJson = """
            {
              "serverContent": {
                "interrupted": true
              }
            }
        """.trimIndent()
        liveService.processServerMessageJson(interruptedJson)
        assertTrue(liveService.isBargeInInterrupted.value)
        assertEquals(1, liveService.bargeInInterruptionCount.value)
        assertEquals("LISTENING", liveService.sessionState.value)
        liveService.stopLiveSession()
    }

    @Test
    fun testOfficialSherpaOnnxAarAndPretrainedModelAssetsAreBundled() {
        val projectAppDir = java.io.File(".").absoluteFile.let { cwd ->
            if (java.io.File(cwd, "libs").exists()) cwd else java.io.File(cwd, "app")
        }

        // 1. Verify official Sherpa-ONNX Android AAR and native .so libraries exist
        val aarFile = java.io.File(projectAppDir, "libs/sherpa-onnx-2.0.3.aar")
        assertTrue("Expected official Sherpa-ONNX AAR at ${aarFile.path}", aarFile.exists() && aarFile.length() > 1_000_000L)

        java.util.zip.ZipFile(aarFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(entries.contains("jni/arm64-v8a/libsherpa-onnx-jni.so"))
            assertTrue(entries.contains("jni/arm64-v8a/libonnxruntime.so"))
            assertTrue(entries.contains("jni/armeabi-v7a/libsherpa-onnx-jni.so"))
            assertTrue(entries.contains("jni/x86_64/libsherpa-onnx-jni.so"))
            assertTrue(entries.contains("classes.jar"))
        }

        // 2. Verify fake compatibility simulation is completely removed
        val fakeCompat = java.io.File(projectAppDir, "src/main/java/com/k2fsa/sherpa/onnx/SherpaOnnxKwsCompat.kt")
        assertFalse("Fake SherpaOnnxKwsCompat.kt must remain removed", fakeCompat.exists())

        // 3. Verify all 5 pretrained KWS model assets are present and non-empty
        val kwsDir = java.io.File(projectAppDir, "src/main/assets/kws-model")
        val requiredAssets = listOf(
            SherpaWakeWordEngine.ENCODER_ASSET_NAME,
            SherpaWakeWordEngine.DECODER_ASSET_NAME,
            SherpaWakeWordEngine.JOINER_ASSET_NAME,
            SherpaWakeWordEngine.TOKENS_ASSET_NAME,
            SherpaWakeWordEngine.KEYWORDS_ASSET_NAME
        )
        requiredAssets.forEach { assetName ->
            val f = java.io.File(kwsDir, assetName)
            assertTrue("Missing or empty KWS asset: ${f.path}", f.exists() && f.length() > 0L)
        }

        // 4. Verify real GigaSpeech tokens.txt tokenizes NAYRO wake phrases into valid in-vocab BPE tokens
        val realVocab = java.io.File(kwsDir, SherpaWakeWordEngine.TOKENS_ASSET_NAME)
            .readLines()
            .mapNotNull { it.trim().split(" ").firstOrNull()?.takeIf { tok -> tok.isNotEmpty() } }
            .toSet()
        assertTrue("Real tokens.txt should contain GigaSpeech BPE vocabulary", realVocab.size >= 400)

        val tokenizer = WakeWordTokenizer(realVocab)
        val spec = tokenizer.buildKeywordsSpec(
            phrases = listOf("HI BABU", "HEY BABU", "HI NAYRO"),
            boostingScore = 2.0f,
            triggerThreshold = 0.25f
        )
        val lines = spec.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        lines.forEach { line ->
            val parts = line.split(" ")
            val tokenParts = parts.takeWhile { !it.startsWith(":") && !it.startsWith("#") && !it.startsWith("@") }
            assertTrue("Expected BPE tokens in line: $line", tokenParts.isNotEmpty())
            tokenParts.forEach { tok ->
                assertTrue("Token '$tok' must exist in real tokens.txt", realVocab.contains(tok))
            }
            val label = parts.last()
            assertTrue("Expected underscore-safe @label at end of line: $line", label.startsWith("@") && !label.contains(" "))
        }
    }

    @Test
    fun testGeminiLiveWebSocketCompatibilitySetupAndDefaultModel() {
        val prefs = SecurePreferences()
        assertEquals("models/gemini-2.0-flash-exp", SecurePreferences.DEFAULT_MODEL)
        assertEquals("models/gemini-2.0-flash-exp", prefs.geminiModel)
        assertTrue(prefs.isMasterPowerOn)

        val ws = com.advanceagent.core.network.GeminiLiveWebSocket(
            apiKey = "test_key",
            modelId = "models/gemini-2.5-flash-native-audio-preview-09-2025",
            voiceName = "Puck",
            userName = "Boss",
            onAudioReceived = {},
            onInterrupted = {},
            onToolCall = {},
            onStateChanged = {}
        )
        val primarySetup = ws.buildInitialSetupMessage(compatibilityMode = false)
        assertEquals("models/gemini-2.5-flash-native-audio-preview-09-2025", primarySetup.setup.model)

        val fallbackSetup = ws.buildInitialSetupMessage(compatibilityMode = true)
        assertEquals("models/gemini-2.0-flash-exp", fallbackSetup.setup.model)
        assertEquals(1, fallbackSetup.setup.tools?.size)
    }

    @Test
    fun testVoiceInteractionServiceForegroundLifecycleAndJniBridgeIntegration() {
        val engine = com.advanceagent.core.wakeword.WakeWordEngine(context = null)
        assertEquals(
            com.advanceagent.core.wakeword.OfficialSherpaOnnxJniBridge,
            engine.jniBridge
        )

        // 1. Foreground active when master power is ON, RECORD_AUDIO is granted, and JNI is ready
        assertEquals(
            com.advanceagent.core.service.VoiceInteractionService.LifecycleState.FOREGROUND_ACTIVE,
            com.advanceagent.core.service.VoiceInteractionService.computeNextLifecycleState(
                action = com.advanceagent.core.service.VoiceInteractionService.ACTION_START_FOREGROUND_MIC,
                hasRecordAudioPermission = true,
                isMasterPowerOn = true,
                isNativeJniReady = true
            )
        )

        // 2. Paused when ACTION_PAUSE_MIC is received
        assertEquals(
            com.advanceagent.core.service.VoiceInteractionService.LifecycleState.PAUSED,
            com.advanceagent.core.service.VoiceInteractionService.computeNextLifecycleState(
                action = com.advanceagent.core.service.VoiceInteractionService.ACTION_PAUSE_MIC,
                hasRecordAudioPermission = true,
                isMasterPowerOn = true,
                isNativeJniReady = true
            )
        )

        // 3. Resumed back to FOREGROUND_ACTIVE on ACTION_RESUME_MIC
        assertEquals(
            com.advanceagent.core.service.VoiceInteractionService.LifecycleState.FOREGROUND_ACTIVE,
            com.advanceagent.core.service.VoiceInteractionService.computeNextLifecycleState(
                action = com.advanceagent.core.service.VoiceInteractionService.ACTION_RESUME_MIC,
                hasRecordAudioPermission = true,
                isMasterPowerOn = true,
                isNativeJniReady = true
            )
        )

        // 4. Error when RECORD_AUDIO is missing
        assertEquals(
            com.advanceagent.core.service.VoiceInteractionService.LifecycleState.ERROR,
            com.advanceagent.core.service.VoiceInteractionService.computeNextLifecycleState(
                action = com.advanceagent.core.service.VoiceInteractionService.ACTION_START_FOREGROUND_MIC,
                hasRecordAudioPermission = false,
                isMasterPowerOn = true,
                isNativeJniReady = true
            )
        )

        // 5. Stopped when ACTION_STOP_SERVICE or master power OFF
        assertEquals(
            com.advanceagent.core.service.VoiceInteractionService.LifecycleState.STOPPED,
            com.advanceagent.core.service.VoiceInteractionService.computeNextLifecycleState(
                action = com.advanceagent.core.service.VoiceInteractionService.ACTION_STOP_SERVICE,
                hasRecordAudioPermission = true,
                isMasterPowerOn = true,
                isNativeJniReady = true
            )
        )
    }
}
