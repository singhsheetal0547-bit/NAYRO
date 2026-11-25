package com.advanceagent.core.tools

import com.advanceagent.core.network.FunctionDeclaration
import com.advanceagent.core.network.ParameterSchema
import com.advanceagent.core.network.PropertySpec
import com.advanceagent.core.network.ToolContainer

object AgentToolDeclarations {

    fun allTools(includeGoogleSearchGrounding: Boolean = false): List<ToolContainer> {
        val list = mutableListOf(
            ToolContainer(
                functionDeclarations = listOf(
                    FunctionDeclaration(
                        name = "open_app",
                        description = "Opens an installed Android app by name.",
                        parameters = ParameterSchema(
                            properties = mapOf("app_name" to PropertySpec("STRING", "Name of the app")),
                            required = listOf("app_name")
                        )
                    ),
                    FunctionDeclaration(
                        name = "call_contact",
                        description = "Dials or calls a contact by name or phone number with disambiguation.",
                        parameters = ParameterSchema(
                            properties = mapOf("target" to PropertySpec("STRING", "Contact name or number")),
                            required = listOf("target")
                        )
                    ),
                    FunctionDeclaration(
                        name = "send_whatsapp",
                        description = "Resolves contact and stages or sends a verified WhatsApp message.",
                        parameters = ParameterSchema(
                            properties = mapOf(
                                "target" to PropertySpec("STRING", "Contact name or phone number"),
                                "message" to PropertySpec("STRING", "Message text")
                            ),
                            required = listOf("target", "message")
                        )
                    ),
                    FunctionDeclaration(
                        name = "confirm_whatsapp_draft",
                        description = "Confirms and sends the currently staged PendingWhatsAppDraft after user approval.",
                        parameters = ParameterSchema(
                            properties = mapOf("draft_id" to PropertySpec("STRING", "Optional draft ID to confirm")),
                            required = emptyList()
                        )
                    ),
                    FunctionDeclaration(
                        name = "search_youtube",
                        description = "Searches and plays a video or song on YouTube (via YouTube Data API v3 or app fallback).",
                        parameters = ParameterSchema(
                            properties = mapOf("query" to PropertySpec("STRING", "Search query")),
                            required = listOf("query")
                        )
                    ),
                    FunctionDeclaration(
                        name = "control_youtube_playback",
                        description = "Controls active media/YouTube playback: PLAY, PAUSE, NEXT, PREVIOUS.",
                        parameters = ParameterSchema(
                            properties = mapOf("action" to PropertySpec("STRING", "PLAY, PAUSE, NEXT, or PREVIOUS")),
                            required = listOf("action")
                        )
                    ),
                    FunctionDeclaration(
                        name = "open_website",
                        description = "Normalizes spoken domain or search query and opens it in Chrome/Browser.",
                        parameters = ParameterSchema(
                            properties = mapOf("target" to PropertySpec("STRING", "Spoken domain (e.g. 'youtube dot com') or URL")),
                            required = listOf("target")
                        )
                    ),
                    FunctionDeclaration(
                        name = "read_screen",
                        description = "Reads all visible and interactive UI elements on the current phone screen.",
                        parameters = ParameterSchema(properties = emptyMap())
                    ),
                    FunctionDeclaration(
                        name = "tap_ui_element",
                        description = "Taps a button or text label visible on the current phone screen.",
                        parameters = ParameterSchema(
                            properties = mapOf("label" to PropertySpec("STRING", "Text label to tap")),
                            required = listOf("label")
                        )
                    ),
                    FunctionDeclaration(
                        name = "tap_coordinates",
                        description = "Taps specific x,y screen coordinates.",
                        parameters = ParameterSchema(
                            properties = mapOf(
                                "x" to PropertySpec("NUMBER", "X pixel coordinate"),
                                "y" to PropertySpec("NUMBER", "Y pixel coordinate")
                            ),
                            required = listOf("x", "y")
                        )
                    ),
                    FunctionDeclaration(
                        name = "type_text",
                        description = "Types text into the currently focused input field on screen.",
                        parameters = ParameterSchema(
                            properties = mapOf("text" to PropertySpec("STRING", "Text to type")),
                            required = listOf("text")
                        )
                    ),
                    FunctionDeclaration(
                        name = "scroll_screen",
                        description = "Scrolls the screen UP, DOWN, LEFT, or RIGHT.",
                        parameters = ParameterSchema(
                            properties = mapOf("direction" to PropertySpec("STRING", "UP, DOWN, LEFT, or RIGHT")),
                            required = listOf("direction")
                        )
                    ),
                    FunctionDeclaration(
                        name = "save_memory",
                        description = "Saves a user preference or personal fact to local memory with deduplication.",
                        parameters = ParameterSchema(
                            properties = mapOf("fact" to PropertySpec("STRING", "Fact to remember")),
                            required = listOf("fact")
                        )
                    ),
                    FunctionDeclaration(
                        name = "device_action",
                        description = "Performs system navigation: HOME, BACK, RECENTS, NOTIFICATIONS.",
                        parameters = ParameterSchema(
                            properties = mapOf("action" to PropertySpec("STRING", "HOME, BACK, RECENTS, or NOTIFICATIONS")),
                            required = listOf("action")
                        )
                    )
                )
            )
        )
        if (includeGoogleSearchGrounding) {
            list.add(ToolContainer(functionDeclarations = null, googleSearch = emptyMap()))
        }
        return list
    }
}
