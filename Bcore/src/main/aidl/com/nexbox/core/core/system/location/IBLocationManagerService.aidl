package com.nexbox.core.core.system.location;

import com.nexbox.core.entity.location.BLocation;
import com.nexbox.core.entity.location.BCell;
import com.nexbox.core.entity.location.BLocationState;

import java.util.List;


interface IBLocationManagerService {
    int getPattern(int userId, String pkg);

    void setPattern(int userId, String pkg, int mode);

    void setCell(int userId, String pkg,in  BCell cell);

    void setAllCell(int userId, String pkg,in  List<BCell> cell);

    void setNeighboringCell(int userId, String pkg,in  List<BCell> cells);
    List<BCell> getNeighboringCell(int userId, String pkg);

    void setGlobalCell(in BCell cell);

    void setGlobalAllCell(in List<BCell> cell);

    void setGlobalNeighboringCell(in List<BCell> cell);

    List<BCell> getGlobalNeighboringCell();

    BCell getCell(int userId, String pkg);

    List<BCell> getAllCell(int userId, String pkg);

    void setLocation(int userId, String pkg,in  BLocation location);

    BLocation getLocation(int userId, String pkg);

    // Raw per-package value, including when its current mode is closed/global (for rollback).
    BLocation getOwnLocation(int userId, String pkg);

    // A generation-checked atomic write. Persistence/identity failures are propagated to callers.
    void setLocationAndPattern(int userId, long userCreateTime, String pkg, in BLocation location, int pattern);

    void setGlobalLocation(in BLocation location);

    BLocation getGlobalLocation();

    void requestLocationUpdates(in IBinder listener, String packageName, int userId);

    void removeUpdates(in IBinder listener);

    // Host-management only. Null packages requests the current installed target snapshot.
    List<BLocationState> getLocationStates(int userId, long userCreateTime, in List<String> packages);

    // The returned states are confirmed only after the entire batch is persisted once.
    List<BLocationState> setLocationStates(int userId, long userCreateTime, in List<BLocationState> states);
}
