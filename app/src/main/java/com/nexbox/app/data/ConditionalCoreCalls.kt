package com.nexbox.app.data

import android.os.Bundle
import com.nexbox.core.utils.MutationReceipt

/** A lost response never replays a write or invents a zero-effect failure. */
internal fun conditionalCoreCall(requestId: String, call: () -> Bundle?): Bundle {
    val fallback = MutationReceipt(requestId)
    fallback.begin("dispatch_core")
    return try {
        val reply = call()
        if (reply == null || reply.getString("requestId") != requestId
            || reply.getString("mutationState") !in setOf("none", "committed", "partial", "unknown")
            || !reply.containsKey("effects") || !reply.containsKey("success")) {
            fallback.failure("invalid_core_receipt")
        } else reply
    } catch (failure: Exception) {
        fallback.data.putBoolean("requestMayHaveExecuted", true)
        fallback.failure("core_response_unavailable")
    }
}
