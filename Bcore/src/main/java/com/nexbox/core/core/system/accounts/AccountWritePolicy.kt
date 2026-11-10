package com.nexbox.core.core.system.accounts

/** A null setter removes a key; a stored null is not the same persisted state as absence. */
class AccountWritePolicy private constructor() {
    companion object {
        @JvmStatic
        fun changesValue(present: Boolean, previous: String?, next: String?): Boolean =
            if (next == null) present else !present || previous != next
    }
}
