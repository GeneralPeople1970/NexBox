package com.nexbox.core.core.system.user;

import com.nexbox.core.core.system.user.BUserInfo;
import android.os.Bundle;
import java.util.List;


interface IBUserManagerService {
    BUserInfo getUserInfo(int userId);
    boolean exists(int userId);
    BUserInfo createUser(int userId);
    List<BUserInfo> getUsers();
    void deleteUser(int userId);
    boolean deleteUserIfMatches(int userId, long createTime, boolean onlyIfEmpty);
    boolean renameUser(int userId, long createTime, String name);
    boolean resetGmsEnvironmentIfMatches(int userId, long userCreateTime);
    boolean hasGmsEnvironmentIfMatches(int userId, long userCreateTime);
    int getNextAvailableUserId();
    // A host-only, generation-bearing display snapshot; this never prunes users.
    List<BUserInfo> getCloneGroups();
    Bundle renameUserWithResult(int userId, long createTime, String name);
    Bundle deleteUserIfInventory(int userId, long createTime, String expectedInventoryToken,
        boolean onlyIfEmpty, String requestId);
    Bundle resetGmsIfInventory(int userId, long createTime, String expectedInventoryToken,
        in List<Bundle> expectedComponents, String requestId);
}
