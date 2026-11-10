package com.nexbox.core.fake.service.context.providers;

import android.net.Uri;

import java.lang.reflect.Method;

import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.media.NexBoxMediaContract;

public class VirtualMediaProviderStub extends SystemProviderStub {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return super.invoke(proxy, method, args);
        }

        // Keep virtual-media URI routing separate from the system Provider caller identity.
        // Neither translation may mutate the caller's argument array or AttributionSource.
        Object[] routedArgs = args == null ? null : args.clone();
        if (routedArgs != null) {
            for (int i = 0; i < routedArgs.length; i++) {
                Object arg = routedArgs[i];
                if (arg instanceof Uri) {
                    routedArgs[i] = NexBoxMediaContract.toPrivateUri((Uri) arg, BActivityThread.getUserId());
                }
            }
        }

        Object result = super.invoke(proxy, method, routedArgs);
        if (result instanceof Uri) {
            return NexBoxMediaContract.toPublicUri((Uri) result);
        }
        return result;
    }
}
