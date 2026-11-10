package com.nexbox.core.core.system.accounts;

import android.accounts.Account;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Typed account-to-visibility IPC payload, separate from BAccount persistence. */
public final class AccountVisibilityMap implements Parcelable {
    private static final int PARCEL_FORMAT = 0x4e424101;

    private final Map<Account, Integer> values;

    /** Takes ownership of the service's detached result snapshot. */
    AccountVisibilityMap(Map<Account, Integer> values) {
        this.values = Objects.requireNonNull(values);
    }

    private AccountVisibilityMap(Parcel source) {
        int count = AccountVisibilityParcel.readCount(source, PARCEL_FORMAT, false);
        // Match the former remote readHashMap; local calls retain their ordered map.
        values = new HashMap<>(count);
        for (int index = 0; index < count; index++) {
            Account account = source.readTypedObject(Account.CREATOR);
            values.put(account, AccountVisibilityParcel.readVisibility(source));
        }
    }

    public Map<Account, Integer> toMap() {
        return values;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(PARCEL_FORMAT);
        dest.writeInt(values.size());
        for (Map.Entry<Account, Integer> entry : values.entrySet()) {
            // writeMap used flags=0. Retain the full Account, including its access ID.
            dest.writeTypedObject(entry.getKey(), 0);
            AccountVisibilityParcel.writeVisibility(dest, entry.getValue());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AccountVisibilityMap> CREATOR = new Creator<AccountVisibilityMap>() {
        @Override
        public AccountVisibilityMap createFromParcel(Parcel source) {
            return new AccountVisibilityMap(source);
        }

        @Override
        public AccountVisibilityMap[] newArray(int size) {
            return new AccountVisibilityMap[size];
        }
    };
}
