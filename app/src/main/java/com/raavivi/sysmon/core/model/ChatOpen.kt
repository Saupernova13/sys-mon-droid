package com.raavivi.sysmon.core.model

/**
 * A request to open the WhatsApp chat [jid] at unix second [ts], flashing message [msgId]. Screens that cannot
 * reach the WhatsApp screen's own state (the Chat index) hand one over through the app container; the WhatsApp
 * screen takes it and clears it.
 */
data class ChatOpenRequest(val jid: String, val ts: Long, val msgId: String?, val name: String = "")
