package com.nexbox.core.core.system.am;
import com.nexbox.core.entity.am.ReceiverData;
interface IBroadcastDelivery {
    oneway void receive(in ReceiverData data);
}
