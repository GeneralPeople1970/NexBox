package com.nexbox.core.core.system.accounts;

/** Deliberately has no account name, account type, credentials, Bundle or error-message input. */
final class AccountSessionDiagnostics {
    enum Operation {
        GET_AUTH_TOKEN("getAuthToken"), ADD_ACCOUNT("addAccount"), REMOVE_ACCOUNT("removeAccount"),
        GET_ACCOUNT_BY_FEATURES("getAccountByTypeAndFeatures"), GET_ACCOUNTS_BY_FEATURES("getAccountsByFeatures"),
        START_ADD_ACCOUNT("startAddAccountSession"), START_UPDATE_CREDENTIALS("startUpdateCredentialsSession"),
        FINISH_SESSION("finishSession"), CREDENTIALS_UPDATE_SUGGESTED("isCredentialsUpdateSuggested"),
        UPDATE_CREDENTIALS("updateCredentials"), EDIT_PROPERTIES("editProperties"),
        CONFIRM_CREDENTIALS("confirmCredentials"), GET_AUTH_TOKEN_LABEL("getAuthTokenLabel");

        final String value;
        Operation(String value) { this.value = value; }
    }

    enum Failure {
        AUTHENTICATOR_MISSING("authenticatorMissing", "local"),
        BIND_REJECTED("bindRejected", "local"), BIND_EXCEPTION("bindException", "local"),
        NULL_BINDER("nullBinder", "local"), UNEXPECTED_SERVICE("unexpectedService", "local"),
        PROCESS_STALE("authenticatorProcessStale", "local"),
        CONNECT_EXCEPTION("connectException", "local"), REQUEST_EXCEPTION("requestException", "local"),
        BINDING_DIED("bindingDied", "local"), DISCONNECTED("disconnected", "local"),
        TIMEOUT("timeout", "local"), INVALID_RESPONSE("invalidResponse", "local"),
        OWNER_CHANGED("ownerChanged", "local"), SESSION_CLOSED("sessionClosed", "local"),
        AUTHENTICATOR_ERROR("authenticatorError", "authenticator"),
        AUTHENTICATOR_RESULT_ERROR("authenticatorResultError", "authenticator");

        final String reason;
        final String origin;
        Failure(String reason, String origin) { this.reason = reason; this.origin = origin; }

        String fields(int code) { return " reason=" + reason + " origin=" + origin + " code=" + code; }
    }

    private AccountSessionDiagnostics() {}

    static String context(Operation operation, long sessionId, int userId, long generation,
                          String callerPackage, String callerProcess, String authenticatorPackage,
                          String authenticatorComponent) {
        return " operation=" + operation.value + " accountSession=" + sessionId
                + " userId=" + userId + " generation=" + generation
                + " callerPackage=" + identifier(callerPackage) + " callerProcess=" + identifier(callerProcess)
                + " authenticatorPackage=" + identifier(authenticatorPackage)
                + " authenticatorComponent=" + identifier(authenticatorComponent);
    }

    private static String identifier(String value) {
        if (value == null || value.isEmpty()) return "absent";
        String bounded = value.length() > 180 ? value.substring(0, 180) : value;
        return bounded.replaceAll("[^A-Za-z0-9_.$/:-]", "_");
    }
}
