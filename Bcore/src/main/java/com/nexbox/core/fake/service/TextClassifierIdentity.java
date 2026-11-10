package com.nexbox.core.fake.service;

import android.os.Parcel;
import android.os.Parcelable;

import com.nexbox.reflection.utils.MemberCache;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Copy the outgoing request: never replace a guest Context, classifier, selection or input text. */
final class TextClassifierIdentity {
    private TextClassifierIdentity() { }

    static Object[] rewrite(Method method, Object[] args, String hostPackage, int hostUserId)
            throws ReflectiveOperationException {
        int index = TextInputCallPolicy.classificationPayload(method.getName(),
                TextInputCallPolicy.parameterNames(method));
        if (index < 0 || args == null || args.length != method.getParameterCount()
                || args[index] == null) return args;
        Object outgoing = copy((Parcelable) args[index], method.getParameterTypes()[index]);
        if ("onTextClassifierEvent".equals(method.getName())) {
            Object context = invoke(outgoing, "getEventContext", new Class<?>[0]);
            if (context != null) mapMetadata(context, hostPackage, hostUserId, true);
        } else {
            boolean packageField = "onCreateTextClassificationSession".equals(method.getName())
                    || "onSelectionEvent".equals(method.getName());
            mapMetadata(outgoing, hostPackage, hostUserId, packageField);
        }
        Object[] mapped = args.clone();
        mapped[index] = outgoing;
        return mapped;
    }

    private static void mapMetadata(Object target, String hostPackage, int hostUserId,
            boolean packageField) throws ReflectiveOperationException {
        Object metadata = invoke(target, "getSystemTextClassifierMetadata", new Class<?>[0]);
        if (metadata != null) {
            Class<?> metadataType = Class.forName("android.view.textclassifier.SystemTextClassifierMetadata");
            boolean useDefault = (Boolean) invoke(metadata, "useDefaultTextClassifier", new Class<?>[0]);
            Constructor<?> constructor = MemberCache.forType(metadataType)
                    .constructor(new Class<?>[]{String.class, int.class, boolean.class});
            constructor.setAccessible(true);
            Object mapped = constructor.newInstance(hostPackage, hostUserId, useDefault);
            invoke(target, "setSystemTextClassifierMetadata", new Class<?>[]{metadataType}, mapped);
        }
        if (packageField) {
            Field field = MemberCache.forType(target.getClass()).field("mPackageName");
            field.setAccessible(true);
            field.set(target, hostPackage);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Method method = MemberCache.forType(target.getClass()).method(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object copy(Parcelable source, Class<?> declaredType)
            throws ReflectiveOperationException {
        Field creatorField = MemberCache.forType(declaredType).field("CREATOR");
        creatorField.setAccessible(true);
        Parcelable.Creator<?> creator = (Parcelable.Creator<?>) creatorField.get(null);
        Parcel parcel = Parcel.obtain();
        try {
            // Do not marshall to bytes: a framework parcel can carry live Binder references.
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
