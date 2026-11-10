package com.nexbox.core.fake.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.security.SecureRandom;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** An opt-in virtual SIM snapshot. It does not activate subscriptions or provide SMS service. */
public final class SimProfile {
    private static final SecureRandom RANDOM = new SecureRandom();
    public final boolean enabled;
    public final List<Card> cards;
    public final int defaultVoiceSubId, defaultDataSubId, defaultSmsSubId;

    public SimProfile(boolean enabled, List<Card> cards, int voice, int data, int sms) {
        this.enabled = enabled;
        this.cards = Collections.unmodifiableList(new ArrayList<>(cards));
        this.defaultVoiceSubId = voice;
        this.defaultDataSubId = data;
        this.defaultSmsSubId = sms;
    }

    public static SimProfile disabled() { return new SimProfile(false, Collections.emptyList(), -1, -1, -1); }
    public static SimProfile noSim() { return new SimProfile(true, Collections.emptyList(), -1, -1, -1); }

    /** A new single-card snapshot. Editor randomization must use randomizedCards(). */
    public static SimProfile randomized() {
        return noSim().withCount(1).randomizedCards();
    }

    /** Randomize values only; never change the user's SIM switch, slot count or defaults. */
    public SimProfile randomizedCards() {
        if (!enabled || cards.isEmpty()) return this;
        List<Card> cards = new ArrayList<>();
        for (Card previous : this.cards) {
            SimCarrier home = SimCarrier.PRESETS.get(RANDOM.nextInt(SimCarrier.PRESETS.size()));
            String seed = UUID.randomUUID().toString();
            cards.add(new Card(previous.slotIndex, previous.subscriptionId, home, home, false,
                    Card.randomPhone(home, seed), seed));
        }
        return new SimProfile(enabled, cards, defaultVoiceSubId, defaultDataSubId, defaultSmsSubId);
    }
    public SimProfile withEnabled(boolean value) {
        return new SimProfile(value, cards, defaultVoiceSubId, defaultDataSubId, defaultSmsSubId);
    }
    public SimProfile withCount(int count) {
        if (count < 0 || count > 2) throw new IllegalArgumentException("SIM count must be 0, 1 or 2");
        List<Card> next = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            Card previous = cardForSlot(slot);
            next.add(previous != null ? previous : new Card(slot, slot + 1, SimCarrier.PRESETS.get(0),
                    SimCarrier.PRESETS.get(0), false, "", UUID.randomUUID().toString()));
        }
        return new SimProfile(true, next, retainDefault(next, defaultVoiceSubId),
                retainDefault(next, defaultDataSubId), retainDefault(next, defaultSmsSubId));
    }
    public SimProfile withCard(Card card) {
        List<Card> next = new ArrayList<>();
        for (Card previous : cards) next.add(previous.slotIndex == card.slotIndex ? card : previous);
        return new SimProfile(enabled, next, defaultVoiceSubId, defaultDataSubId, defaultSmsSubId);
    }
    public SimProfile withDefaults(int voice, int data, int sms) { return new SimProfile(enabled, cards, voice, data, sms); }
    public SimProfile withFormattedPhoneNumbers() {
        List<Card> formatted = new ArrayList<>();
        for (Card card : cards) formatted.add(card.withPhoneNumber(card.formattedPhoneNumber()));
        return new SimProfile(enabled, formatted, defaultVoiceSubId, defaultDataSubId, defaultSmsSubId);
    }
    public Card cardForSlot(int slot) {
        for (Card card : cards) if (card.slotIndex == slot) return card;
        return null;
    }
    public Card cardForSubscription(int id) {
        if (id == Integer.MAX_VALUE) id = defaultDataSubId;
        for (Card card : cards) if (card.subscriptionId == id) return card;
        return null;
    }
    public Card defaultCard() { return cardForSubscription(defaultDataSubId); }
    private static int retainDefault(List<Card> cards, int id) {
        for (Card card : cards) if (card.subscriptionId == id) return id;
        return cards.isEmpty() ? -1 : cards.get(0).subscriptionId;
    }

    /** Returns a validation detail, or null. A phone number can intentionally be unavailable. */
    public String validationError() {
        if (!enabled) return null;
        if (cards.size() > 2) return "At most two virtual SIM cards are supported";
        Set<Integer> slots = new HashSet<>(), ids = new HashSet<>();
        Set<String> imsis = new HashSet<>(), iccids = new HashSet<>();
        for (Card card : cards) {
            if (card.slotIndex < 0 || card.slotIndex > 1 || card.subscriptionId <= 0
                    || !slots.add(card.slotIndex) || !ids.add(card.subscriptionId)) return "Invalid SIM slot/subscription mapping";
            if (!card.home.isValid() || !card.network.isValid()) return "Invalid SIM country/operator";
            if (card.seed == null || card.seed.isEmpty() || !card.isValidImsi() || !card.isValidIccid()
                    || !imsis.add(card.imsi) || !iccids.add(card.iccid)) return "Invalid or duplicate virtual SIM identifiers";
            if (!card.isValidPhoneNumber()) {
                return "Phone number must use the SIM country calling code and E.164 format";
            }
        }
        if (cards.isEmpty()) {
            return defaultVoiceSubId == -1 && defaultDataSubId == -1 && defaultSmsSubId == -1 ? null : "A no-SIM profile cannot have a default subscription";
        }
        for (int slot = 0; slot < cards.size(); slot++) {
            if (!slots.contains(slot)) return "Virtual slots must be contiguous from zero";
        }
        return ids.contains(defaultVoiceSubId) && ids.contains(defaultDataSubId) && ids.contains(defaultSmsSubId)
                ? null : "Default subscription must refer to an active virtual SIM";
    }

    public String toJson() {
        try {
            JSONArray array = new JSONArray();
            for (Card card : cards) array.put(card.toJson());
            return new JSONObject().put("version", 1).put("enabled", enabled).put("cards", array)
                    .put("voice", defaultVoiceSubId).put("data", defaultDataSubId).put("sms", defaultSmsSubId).toString();
        } catch (JSONException e) { throw new IllegalStateException("Could not serialize SIM profile", e); }
    }
    public static SimProfile fromJson(String json) {
        if (json == null || json.isEmpty()) return disabled();
        try {
            SimProfile profile = decodeJson(json);
            return profile.validationError() == null ? profile.withFormattedPhoneNumbers() : noSim();
        } catch (JSONException | IllegalArgumentException e) {
            // A corrupted opt-in profile must not silently expose the host's SIM instead.
            return noSim();
        }
    }

    /**
     * Recreates editor-owned state, including incomplete phone input. Never use this method to
     * load an active device profile: persisted/runtime profiles must go through {@link #fromJson}.
     */
    public static SimProfile fromDraftJson(String json) {
        try {
            return decodeJson(json);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid SIM editor state", e);
        }
    }

    private static SimProfile decodeJson(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        if (object.getInt("version") != 1) throw new IllegalArgumentException("Unsupported SIM profile version");
        List<Card> cards = new ArrayList<>();
        JSONArray array = object.getJSONArray("cards");
        if (array.length() > 2) throw new IllegalArgumentException("Too many virtual SIM cards");
        for (int i = 0; i < array.length(); i++) cards.add(Card.fromJson(array.getJSONObject(i)));
        return new SimProfile(object.getBoolean("enabled"), cards,
                object.getInt("voice"), object.getInt("data"), object.getInt("sms"));
    }

    public static final class Card {
        public final int slotIndex, subscriptionId;
        public final SimCarrier home, network;
        public final boolean roaming;
        public final String phoneNumber, seed, imsi, iccid;

        public Card(int slotIndex, int subscriptionId, SimCarrier home, SimCarrier network,
                boolean roaming, String phoneNumber, String seed) {
            this(slotIndex, subscriptionId, home, network, roaming, phoneNumber, seed, null, null);
        }

        private Card(int slotIndex, int subscriptionId, SimCarrier home, SimCarrier network,
                boolean roaming, String phoneNumber, String seed, String imsi, String iccid) {
            this.slotIndex = slotIndex;
            this.subscriptionId = subscriptionId;
            this.home = home;
            // Roaming controls were removed. Normalize older snapshots as well, so every
            // property, subscription and telephony hook agrees on the home network.
            this.network = home;
            this.roaming = false;
            // Preserve incomplete editor text. Formatting is explicit on blur/save, not on
            // every keystroke, and runtime APIs obtain the canonical E.164 representation.
            this.phoneNumber = phoneNumber == null ? "" : phoneNumber;
            this.seed = seed;
            this.imsi = imsi != null ? imsi : generatedImsi(home, seed);
            this.iccid = iccid != null ? iccid : generatedIccid(home, seed);
        }

        private static String generatedImsi(SimCarrier home, String seed) {
            return home.numeric() + digits(seed + "/imsi/" + home.numeric(), 15 - home.numeric().length());
        }

        private static String generatedIccid(SimCarrier home, String seed) {
            // Synthetic issuer 99, NOT an allocated carrier ICCID. Country prefix follows E.118.
            String prefix = "89" + home.dialCode + "99";
            String base = prefix + digits(seed + "/iccid/" + home.dialCode, 18 - prefix.length());
            return base + checkDigit(base);
        }
        public Card withHome(SimCarrier carrier) {
            if (home.numeric().equals(carrier.numeric()) && home.dialCode.equals(carrier.dialCode)) return this;
            return new Card(slotIndex, subscriptionId, carrier, network, roaming,
                    phoneNumber.startsWith("+" + carrier.dialCode) ? phoneNumber : "", seed);
        }
        public Card withNetwork(SimCarrier carrier) { return new Card(slotIndex, subscriptionId, home, carrier, true, phoneNumber, seed, imsi, iccid); }
        public Card withRoaming(boolean value) { return new Card(slotIndex, subscriptionId, home, network, value, phoneNumber, seed, imsi, iccid); }
        public Card withPhoneNumber(String value) { return new Card(slotIndex, subscriptionId, home, network, roaming, value, seed, imsi, iccid); }
        public Card withImsi(String value) { return new Card(slotIndex, subscriptionId, home, network, roaming, phoneNumber, seed, value, iccid); }
        public Card withIccid(String value) { return new Card(slotIndex, subscriptionId, home, network, roaming, phoneNumber, seed, imsi, value); }
        public Card withRandomPhoneNumber() { return withPhoneNumber(randomPhone(home, UUID.randomUUID().toString())); }
        public Card withRandomImsi() { return withImsi(generatedImsi(home, UUID.randomUUID().toString())); }
        public Card withRandomIccid() { return withIccid(generatedIccid(home, UUID.randomUUID().toString())); }

        public boolean isValidImsi() {
            return imsi != null && imsi.matches("[0-9]{14,15}") && imsi.startsWith(home.numeric());
        }
        public boolean isValidIccid() {
            return iccid != null && iccid.matches("[0-9]{19,20}") && iccid.startsWith("89" + home.dialCode)
                    && iccid.charAt(iccid.length() - 1) - '0' == checkDigit(iccid.substring(0, iccid.length() - 1));
        }
        public boolean isValidPhoneNumber() {
            String number = e164PhoneNumber();
            return number.isEmpty() || (number.matches("\\+[1-9][0-9]{3,14}")
                    && number.startsWith("+" + home.dialCode)
                    && number.length() > home.dialCode.length() + 1);
        }
        public String e164PhoneNumber() { return phoneNumber.replaceAll("\\s", ""); }
        public String formattedPhoneNumber() {
            String number = e164PhoneNumber();
            String prefix = "+" + home.dialCode;
            if (!number.startsWith(prefix) || number.length() <= prefix.length()
                    || !number.matches("\\+[1-9][0-9]*")) return phoneNumber;
            return prefix + " " + number.substring(prefix.length());
        }
        public Card randomized() { return new Card(slotIndex, subscriptionId, home, network, roaming, phoneNumber, UUID.randomUUID().toString()); }
        private static String randomPhone(SimCarrier home, String seed) {
            // Synthetic display values only; no real subscriber data or SMS capability.
            String prefix;
            int length;
            switch (home.countryIso) {
                case "cn": prefix = "138"; length = 11; break;
                case "hk": case "mo": prefix = "6"; length = 8; break;
                case "tw": prefix = "9"; length = 9; break;
                case "jp": prefix = "90"; length = 10; break;
                case "kr": prefix = "10"; length = 10; break;
                case "gb": prefix = "7"; length = 10; break;
                case "us": prefix = "202"; length = 10; break;
                case "ca": prefix = "416"; length = 10; break;
                case "sg": prefix = "9"; length = 8; break;
                case "au": prefix = "4"; length = 9; break;
                case "nz": prefix = "21"; length = 9; break;
                case "th": case "vn": prefix = "9"; length = 9; break;
                case "my": prefix = "12"; length = 9; break;
                case "id": prefix = "812"; length = 11; break;
                case "ph": prefix = "917"; length = 10; break;
                case "in": prefix = "98"; length = 10; break;
                case "de": prefix = "151"; length = 11; break;
                case "fr": prefix = "6"; length = 9; break;
                case "it": prefix = "347"; length = 10; break;
                case "es": prefix = "6"; length = 9; break;
                case "ae": case "sa": prefix = "5"; length = 9; break;
                case "tr": prefix = "532"; length = 10; break;
                default: prefix = "9"; length = 10; break;
            }
            return "+" + home.dialCode + " " + prefix + digits(seed + "/phone", length - prefix.length());
        }
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("slot", slotIndex).put("subId", subscriptionId).put("home", home.toJson())
                    .put("network", network.toJson()).put("roaming", roaming).put("phone", phoneNumber).put("seed", seed)
                    .put("imsi", imsi).put("iccid", iccid);
        }
        static Card fromJson(JSONObject value) throws JSONException {
            SimCarrier home = SimCarrier.fromJson(value.getJSONObject("home"));
            return new Card(value.getInt("slot"), value.getInt("subId"), home,
                    home, false, value.optString("phone"), value.getString("seed"),
                    value.has("imsi") ? value.getString("imsi") : null,
                    value.has("iccid") ? value.getString("iccid") : null);
        }
        private static String digits(String seed, int count) {
            if (count < 0 || count > 16) return "";
            String hash = DeviceIdentifierGenerator.stableAndroidId(seed);
            StringBuilder result = new StringBuilder(count);
            for (int i = 0; i < count; i++) result.append(Character.digit(hash.charAt(i), 16) % 10);
            return result.toString();
        }
        private static int checkDigit(String number) {
            int sum = 0;
            boolean twice = true;
            for (int i = number.length() - 1; i >= 0; i--) {
                int digit = number.charAt(i) - '0';
                if (twice) digit *= 2;
                sum += digit / 10 + digit % 10;
                twice = !twice;
            }
            return (10 - sum % 10) % 10;
        }
    }
}
