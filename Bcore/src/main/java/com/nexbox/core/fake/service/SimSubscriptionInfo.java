package com.nexbox.core.fake.service;

import android.telephony.SubscriptionInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import com.nexbox.core.fake.device.SimProfile;

/** Builds a fresh framework object; never copies a host subscription's hidden identity fields. */
final class SimSubscriptionInfo {
    private SimSubscriptionInfo() {}

    static SubscriptionInfo create(SimProfile.Card card) {
        try {
            Class<?> type = Class.forName("android.telephony.SubscriptionInfo$Builder");
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object builder = constructor.newInstance();
            set(builder, "setId", int.class, card.subscriptionId);
            set(builder, "setIccId", String.class, card.iccid);
            set(builder, "setSimSlotIndex", int.class, card.slotIndex);
            set(builder, "setDisplayName", CharSequence.class, card.home.name);
            set(builder, "setCarrierName", CharSequence.class, card.home.name);
            set(builder, "setNumber", String.class, card.e164PhoneNumber());
            set(builder, "setMcc", String.class, card.home.mcc);
            set(builder, "setMnc", String.class, card.home.mnc);
            set(builder, "setCountryIso", String.class, card.home.countryIso);
            // A roaming observation is not permission to enable paid data roaming.
            set(builder, "setDataRoaming", int.class, 0);
            try { set(builder, "setCardString", String.class, card.iccid); } catch (ReflectiveOperationException ignored) {}
            Method build = type.getDeclaredMethod("build");
            build.setAccessible(true);
            return (SubscriptionInfo) build.invoke(builder);
        } catch (ReflectiveOperationException builderUnavailable) {
            // Android 12's constructor predates SubscriptionInfo.Builder. Later optional
            // parameters receive empty defaults, not values obtained from a physical SIM.
            Constructor<?>[] constructors = SubscriptionInfo.class.getDeclaredConstructors();
            Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
            for (Constructor<?> constructor : constructors) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length < 16 || types[0] != int.class || types[1] != String.class
                        || types[2] != int.class || types[10] != String.class || types[11] != String.class) continue;
                Object[] args = new Object[types.length];
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == int.class) args[i] = -1;
                    else if (types[i] == boolean.class) args[i] = false;
                    else if (types[i] == long.class) args[i] = 0L;
                }
                Object[] first = {card.subscriptionId, card.iccid, card.slotIndex, card.home.name,
                        card.home.name, 0, 0, card.e164PhoneNumber(), 0, null, card.home.mcc, card.home.mnc,
                        card.home.countryIso, false, null, card.iccid};
                System.arraycopy(first, 0, args, 0, first.length);
                try {
                    constructor.setAccessible(true);
                    return (SubscriptionInfo) constructor.newInstance(args);
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {}
            }
            throw new IllegalStateException("This Android version cannot construct virtual SubscriptionInfo", builderUnavailable);
        }
    }

    private static void set(Object target, String name, Class<?> type, Object value) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(target, value);
    }
}
