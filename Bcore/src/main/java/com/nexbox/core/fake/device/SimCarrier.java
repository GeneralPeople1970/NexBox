package com.nexbox.core.fake.device;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Small, explicit operator presets. MNC is a string so leading zeros are not lost. */
public final class SimCarrier {
    public final String countryIso, dialCode, mcc, mnc, name;
    public static final List<SimCarrier> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new SimCarrier("cn", "86", "460", "00", "China Mobile"),
            new SimCarrier("hk", "852", "454", "12", "China Mobile HK"),
            new SimCarrier("mo", "853", "455", "01", "CTM"),
            new SimCarrier("tw", "886", "466", "92", "Chunghwa Telecom"),
            new SimCarrier("us", "1", "310", "260", "T-Mobile US"),
            new SimCarrier("ca", "1", "302", "720", "Rogers"),
            new SimCarrier("gb", "44", "234", "15", "Vodafone UK"),
            new SimCarrier("jp", "81", "440", "10", "NTT DOCOMO"),
            new SimCarrier("kr", "82", "450", "05", "SK Telecom"),
            new SimCarrier("sg", "65", "525", "01", "Singtel"),
            new SimCarrier("th", "66", "520", "01", "AIS"),
            new SimCarrier("my", "60", "502", "12", "Maxis"),
            new SimCarrier("id", "62", "510", "10", "Telkomsel"),
            new SimCarrier("vn", "84", "452", "04", "Viettel"),
            new SimCarrier("ph", "63", "515", "02", "Globe"),
            new SimCarrier("in", "91", "404", "45", "Airtel"),
            new SimCarrier("au", "61", "505", "01", "Telstra"),
            new SimCarrier("nz", "64", "530", "01", "One NZ"),
            new SimCarrier("de", "49", "262", "02", "Vodafone DE"),
            new SimCarrier("fr", "33", "208", "01", "Orange"),
            new SimCarrier("it", "39", "222", "10", "Vodafone IT"),
            new SimCarrier("es", "34", "214", "07", "Movistar"),
            new SimCarrier("ae", "971", "424", "02", "e& UAE"),
            new SimCarrier("sa", "966", "420", "01", "stc"),
            new SimCarrier("tr", "90", "286", "01", "Turkcell")));

    public SimCarrier(String countryIso, String dialCode, String mcc, String mnc, String name) {
        this.countryIso = countryIso.toLowerCase(Locale.ROOT);
        this.dialCode = dialCode;
        this.mcc = mcc;
        this.mnc = mnc;
        this.name = name;
    }

    public String numeric() { return mcc + mnc; }
    public String label() { return name + " (" + countryIso.toUpperCase(Locale.ROOT) + ")"; }
    public boolean isValid() {
        if (!countryIso.matches("[a-z]{2}") || !dialCode.matches("[1-9][0-9]{0,2}")
                || !mcc.matches("[0-9]{3}") || !mnc.matches("[0-9]{2,3}") || name.trim().isEmpty()) return false;
        for (SimCarrier known : PRESETS) {
            if (known.countryIso.equals(countryIso) && !known.dialCode.equals(dialCode)) return false;
            if (known.mcc.equals(mcc) && !known.countryIso.equals(countryIso)) return false;
        }
        return true;
    }
    JSONObject toJson() throws JSONException {
        return new JSONObject().put("country", countryIso).put("dialCode", dialCode)
                .put("mcc", mcc).put("mnc", mnc).put("name", name);
    }
    static SimCarrier fromJson(JSONObject value) throws JSONException {
        return new SimCarrier(value.getString("country"), value.getString("dialCode"),
                value.getString("mcc"), value.getString("mnc"), value.getString("name"));
    }
}
