package com.nexbox.core.fake.service;

import java.lang.reflect.Method;
import java.util.Arrays;

/** Only known framework signatures carry identities; widget tokens and display IDs are not users. */
final class TextInputCallPolicy {
    private static final String SESSION = "android.view.textclassifier.TextClassificationSessionId";
    private static final String CALLBACK = "android.service.textclassifier.ITextClassifierCallback";
    private static final String CLASSIFIER = "android.view.textclassifier.";

    private TextInputCallPolicy() { }

    static String[] parameterNames(Method method) {
        Class<?>[] types = method.getParameterTypes();
        String[] names = new String[types.length];
        for (int index = 0; index < types.length; index++) names[index] = types[index].getName();
        return names;
    }

    static int classificationPayload(String name, String[] types) {
        String payload;
        switch (name) {
            case "onSuggestSelection": payload = "TextSelection$Request"; break;
            case "onClassifyText": payload = "TextClassification$Request"; break;
            case "onGenerateLinks": payload = "TextLinks$Request"; break;
            case "onDetectLanguage": payload = "TextLanguage$Request"; break;
            case "onSuggestConversationActions": payload = "ConversationActions$Request"; break;
            case "onSelectionEvent":
                return matches(types, SESSION, CLASSIFIER + "SelectionEvent") ? 1 : -1;
            case "onTextClassifierEvent":
                return matches(types, SESSION, CLASSIFIER + "TextClassifierEvent") ? 1 : -1;
            case "onCreateTextClassificationSession":
                return matches(types, CLASSIFIER + "TextClassificationContext", SESSION) ? 0 : -1;
            default: return -1;
        }
        return matches(types, SESSION, CLASSIFIER + payload, CALLBACK) ? 1 : -1;
    }

    static Object[] toolbarUser(String name, String[] types, Object[] args, int hostUserId) {
        if (args == null || types.length != args.length || hostUserId < 0) return args;
        int index = -1;
        if ("showToolbar".equals(name) && matches(types,
                "android.view.selectiontoolbar.ShowInfo",
                "android.view.selectiontoolbar.ISelectionToolbarCallback", "int")) {
            index = 2;
        } else if (("hideToolbar".equals(name) || "dismissToolbar".equals(name))
                && matches(types, "long", "int")) {
            index = 1;
        }
        // Newer releases infer the caller from Binder and have no userId argument.
        if (index < 0) return args;
        Object[] mapped = args.clone();
        mapped[index] = hostUserId;
        return mapped;
    }

    static Object[] windowUser(String name, String[] types, Object[] args, int hostUserId) {
        if (!"addToDisplayAsUser".equals(name) || args == null || hostUserId < 0
                || types.length != args.length || types.length < 5
                || !"android.view.IWindow".equals(types[0])
                || !"android.view.WindowManager$LayoutParams".equals(types[1])
                || !"int".equals(types[2]) || !"int".equals(types[3])
                || !"int".equals(types[4])) return args;
        Object[] mapped = args.clone();
        mapped[4] = hostUserId;
        return mapped;
    }

    static boolean windowLayout(String name) {
        return "addToDisplay".equals(name) || "addToDisplayAsUser".equals(name)
                || "addToDisplayWithoutInputChannel".equals(name)
                || "relayout".equals(name) || "relayoutAsync".equals(name);
    }

    private static boolean matches(String[] actual, String... expected) {
        return Arrays.equals(actual, expected);
    }
}
