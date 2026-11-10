package com.nexbox.core.fake.service;

import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.nexbox.core.fake.device.DeviceSpoofManager;
import com.nexbox.core.fake.device.SimProfile;

/** Shared read-only SIM view for ITelephony, IPhoneSubInfo and ISub. */
public final class SimServiceHooks {
    public static final Object UNHANDLED = new Object();
    private SimServiceHooks() {}

    public static Object handle(boolean subscriptionService, Method method, Object[] args) {
        if (!DeviceSpoofManager.isCurrentProcessEnabled()) return UNHANDLED;
        SimProfile profile = DeviceSpoofManager.getCurrentProfile().simProfile;
        if (!profile.enabled) return UNHANDLED;
        String name = method.getName();
        int id = firstInt(args, Integer.MAX_VALUE);
        SimProfile.Card card = profile.cardForSubscription(id);
        if (subscriptionService) {
            if (name.startsWith("set") || name.startsWith("add") || name.startsWith("remove")
                    || name.startsWith("clear") || name.startsWith("create") || name.startsWith("restore")) {
                throw new SecurityException("Virtual SIM profiles are read-only; change them in NexBox settings");
            }
            switch (name) {
                case "getAllSubInfoList": case "getActiveSubscriptionInfoList":
                case "getAvailableSubscriptionInfoList": case "getSubscriptionInfoListAssociatedWithUser":
                    return subscriptions(profile);
                case "getAccessibleSubscriptionInfoList": case "getOpportunisticSubscriptions":
                case "getSubscriptionsInGroup": return Collections.emptyList();
                case "getAllSubInfoCount": case "getActiveSubInfoCount": return profile.cards.size();
                case "getActiveSubInfoCountMax": return 2;
                case "getActiveSubIdList": {
                    int[] ids = new int[profile.cards.size()];
                    for (int i = 0; i < ids.length; i++) ids[i] = profile.cards.get(i).subscriptionId;
                    return ids;
                }
                case "getDefaultSubId": case "getDefaultSubIdAsUser": case "getDefaultDataSubId":
                case "getActiveDataSubscriptionId": case "getPreferredDataSubscriptionId": return profile.defaultDataSubId;
                case "getDefaultVoiceSubId": case "getDefaultVoiceSubIdAsUser": return profile.defaultVoiceSubId;
                case "getDefaultSmsSubId": case "getDefaultSmsSubIdAsUser": return profile.defaultSmsSubId;
                case "getActiveSubscriptionInfo": return card == null ? null : SimSubscriptionInfo.create(card);
                case "getActiveSubscriptionInfoForSimSlotIndex": {
                    SimProfile.Card slot = profile.cardForSlot(id);
                    return slot == null ? null : SimSubscriptionInfo.create(slot);
                }
                case "getActiveSubscriptionInfoForIccId": {
                    String iccid = firstString(args);
                    for (SimProfile.Card item : profile.cards) if (item.iccid.equals(iccid)) return SimSubscriptionInfo.create(item);
                    return null;
                }
                case "getSlotIndex": case "getSlotId": case "getPhoneId": return card == null ? -1 : card.slotIndex;
                case "getSubId": case "getSubscriptionId": case "getEnabledSubscriptionId": {
                    SimProfile.Card slot = profile.cardForSlot(id);
                    if (method.getReturnType() == int[].class) return slot == null ? new int[0] : new int[]{slot.subscriptionId};
                    return slot == null ? -1 : slot.subscriptionId;
                }
                case "isActiveSubId": case "isSubscriptionEnabled":
                case "isSubscriptionAssociatedWithCallingUser": case "isSubscriptionAssociatedWithUser": return card != null;
                case "canDisablePhysicalSubscription": return false;
                case "getSimStateForSlotIndex": return simState(profile.cardForSlot(id));
                case "getPhoneNumber": case "getPhoneNumberFromFirstAvailableSource": return card == null ? "" : card.e164PhoneNumber();
                case "getSubscriptionProperty": return subscriptionProperty(card, args);
            }
        }
        switch (name) {
            case "getSubscriberId": case "getSubscriberIdForSubscriber": return card == null ? null : card.imsi;
            case "getIccSerialNumber": case "getIccSerialNumberForSubscriber": return card == null ? null : card.iccid;
            case "getLine1Number": case "getLine1NumberForSubscriber": case "getLine1NumberForDisplay":
            case "getMsisdn": case "getMsisdnForSubscriber": return card == null || card.e164PhoneNumber().isEmpty() ? null : card.e164PhoneNumber();
            case "getLine1AlphaTag": case "getLine1AlphaTagForSubscriber": return card == null ? null : card.home.name;
            case "getSimOperator": case "getSimOperatorNumeric": return card == null ? "" : card.home.numeric();
            case "getSimOperatorName": return card == null ? "" : card.home.name;
            case "getSimCountryIso": case "getSimCountryIsoForSubscriber": return card == null ? "" : card.home.countryIso;
            case "getNetworkOperator": case "getNetworkOperatorForSubscriber": return card == null ? "" : card.network.numeric();
            case "getNetworkOperatorName": return card == null ? "" : card.network.name;
            case "getNetworkCountryIsoForSubscriber": return card == null ? "" : card.network.countryIso;
            case "isNetworkRoaming": return card != null && card.roaming;
            case "getSimOperatorNumericForPhone": case "getSimCountryIsoForPhone":
            case "getNetworkCountryIsoForPhone": case "getNetworkCountryIso": {
                SimProfile.Card slot = profile.cardForSlot(id == Integer.MAX_VALUE ? 0 : id);
                if (slot == null) return "";
                if ("getSimOperatorNumericForPhone".equals(name)) return slot.home.numeric();
                return "getSimCountryIsoForPhone".equals(name) ? slot.home.countryIso : slot.network.countryIso;
            }
            case "hasIccCard": case "hasIccCardUsingSlotIndex": return profile.cardForSlot(id == Integer.MAX_VALUE ? 0 : id) != null;
            case "getSimStateForSlotIndex": case "getSimState": return simState(profile.cardForSlot(id == Integer.MAX_VALUE ? 0 : id));
            case "getSimApplicationStateForSlotIndex": return profile.cardForSlot(id) == null ? TelephonyManager.SIM_STATE_UNKNOWN : 10;
            case "getSimCardStateForSlotIndex": return profile.cardForSlot(id) == null ? TelephonyManager.SIM_STATE_ABSENT : 11;
            case "getActiveModemCount": case "getPhoneCount": return Math.max(1, profile.cards.size());
            case "getSimCarrierId": case "getSubscriptionCarrierId": return -1;
            case "getSimCarrierIdName": case "getSubscriptionCarrierName": return card == null ? null : card.home.name;
            case "getAllCellInfo": case "getNeighboringCellInfo": return Collections.emptyList();
            case "getCellLocation": return null;
            case "getNetworkTypeForSubscriber": case "getDataNetworkTypeForSubscriber":
            case "getVoiceNetworkTypeForSubscriber": return TelephonyManager.NETWORK_TYPE_UNKNOWN;
            case "getServiceStateForSubscriber": return serviceState(card);
            case "setLine1NumberForDisplay": throw new SecurityException("Set a virtual phone number in NexBox settings");
            default: return UNHANDLED;
        }
    }

    private static int simState(SimProfile.Card card) { return card == null ? TelephonyManager.SIM_STATE_ABSENT : TelephonyManager.SIM_STATE_READY; }
    private static int firstInt(Object[] args, int fallback) {
        if (args != null) for (Object arg : args) if (arg instanceof Integer) return (Integer) arg;
        return fallback;
    }
    private static String firstString(Object[] args) {
        if (args != null) for (Object arg : args) if (arg instanceof String) return (String) arg;
        return "";
    }
    private static List<SubscriptionInfo> subscriptions(SimProfile profile) {
        List<SubscriptionInfo> result = new ArrayList<>();
        for (SimProfile.Card card : profile.cards) result.add(SimSubscriptionInfo.create(card));
        return result;
    }
    private static String subscriptionProperty(SimProfile.Card card, Object[] args) {
        if (card == null) return null;
        switch (firstString(args)) {
            case "icc_id": return card.iccid;
            case "number": return card.e164PhoneNumber();
            case "mcc": case "mcc_string": return card.home.mcc;
            case "mnc": case "mnc_string": return card.home.mnc;
            case "iso_country_code": return card.home.countryIso;
            case "carrier_name": case "display_name": return card.home.name;
            case "sim_id": return String.valueOf(card.slotIndex);
            case "data_roaming": return "0";
            default: return null;
        }
    }
    private static ServiceState serviceState(SimProfile.Card card) {
        ServiceState state = new ServiceState();
        // There is no modem registration behind a virtual SIM. Never claim an actual data link.
        try {
            ServiceState.class.getMethod("setStateOutOfService").invoke(state);
            if (card != null) {
                ServiceState.class.getMethod("setOperatorName", String.class, String.class, String.class)
                        .invoke(state, card.network.name, card.network.name, card.network.numeric());
                ServiceState.class.getMethod("setRoaming", boolean.class).invoke(state, card.roaming);
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unable to create virtual service state", e); }
        return state;
    }
}
