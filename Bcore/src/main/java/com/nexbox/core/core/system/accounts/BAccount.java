package com.nexbox.core.core.system.accounts;

import android.accounts.Account;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.nexbox.core.utils.compat.ParcelCompat;


public class BAccount implements Parcelable {
    public Account account;
    public String password;
    public HashMap<String, String> accountUserData = new LinkedHashMap<>();
    public HashMap<String, Integer> visibility = new LinkedHashMap<>();
    public HashMap<String, String> authTokens = new LinkedHashMap<>();
    public long updateLastAuthenticatedTime;

    public boolean isMatch(Account account) {
        if (account == null) return false;
        return account.equals(this.account);
    }

    public void insertExtra(String key, String value) {
        this.accountUserData.put(key, value);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(this.account, flags);
        dest.writeString(this.password);
        dest.writeSerializable(this.accountUserData);
        dest.writeSerializable(this.visibility);
        dest.writeSerializable(this.authTokens);
        dest.writeLong(this.updateLastAuthenticatedTime);
    }

    public void readFromParcel(Parcel source) {
        this.account = ParcelCompat.readParcelable(source, Account.class.getClassLoader(), Account.class);
        this.password = source.readString();
        this.accountUserData = readAccountMap(source, String.class);
        this.visibility = readAccountMap(source, Integer.class);
        this.authTokens = readAccountMap(source, String.class);
        this.updateLastAuthenticatedTime = source.readLong();
    }

    public BAccount() {
    }

    protected BAccount(Parcel in) {
        this.account = ParcelCompat.readParcelable(in, Account.class.getClassLoader(), Account.class);
        this.password = in.readString();
        this.accountUserData = readAccountMap(in, String.class);
        this.visibility = readAccountMap(in, Integer.class);
        this.authTokens = readAccountMap(in, String.class);
        this.updateLastAuthenticatedTime = in.readLong();
    }

    private static <V> HashMap<String, V> readAccountMap(Parcel source, Class<V> valueType) {
        HashMap<?, ?> stored;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stored = source.readSerializable(BAccount.class.getClassLoader(), HashMap.class);
        } else {
            // API 31-32 have no class-token reader; keep the existing Serializable wire format.
            stored = (HashMap<?, ?>) ParcelCompat.readSerializable(source);
        }
        if (stored == null) return null;

        // Account writers use insertion-ordered maps; also accept historical plain HashMaps.
        // Validate before the registry is published, without discarding nullable entries.
        HashMap<String, V> values = stored instanceof LinkedHashMap<?, ?>
                ? new LinkedHashMap<>() : new HashMap<>();
        for (Map.Entry<?, ?> entry : stored.entrySet()) {
            values.put(String.class.cast(entry.getKey()), valueType.cast(entry.getValue()));
        }
        return values;
    }

    public static final Creator<BAccount> CREATOR = new Creator<BAccount>() {
        @Override
        public BAccount createFromParcel(Parcel source) {
            return new BAccount(source);
        }

        @Override
        public BAccount[] newArray(int size) {
            return new BAccount[size];
        }
    };
}
