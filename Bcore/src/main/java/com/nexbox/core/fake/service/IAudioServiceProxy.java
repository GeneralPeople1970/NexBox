package com.nexbox.core.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.utils.Slog;


public class IAudioServiceProxy extends BinderInvocationStub {
    public static final String TAG = "AudioServiceProxy";
    private final IBinder platformBinder;
    private IInterface platformService;

    public IAudioServiceProxy() {
        this(originalBinder());
    }

    private IAudioServiceProxy(IBinder binder) {
        super(binder);
        platformBinder = binder;
    }

    private static IBinder originalBinder() {
        IBinder binder = BRServiceManager.get().getService(Context.AUDIO_SERVICE);
        while (binder instanceof IAudioServiceProxy) binder = ((IAudioServiceProxy) binder).platformBinder;
        return binder;
    }

    @Override
    protected Object getWho() {
        if (platformBinder == null) {
            Slog.w(TAG, "stage=unavailable reason=serviceMissing");
            return null;
        }
        if (platformService == null) {
            try {
                platformService = (IInterface) Class.forName("android.media.IAudioService$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, platformBinder);
                if (platformService == null) throw new IllegalStateException("Audio interface is null");
                Slog.d(TAG, "stage=resolved interface=IAudioService");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Audio service interface is unavailable", failure);
            }
        }
        return platformService;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    
    @ProxyMethod("isMicrophoneMuted")
    public static class IsMicrophoneMuted extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: isMicrophoneMuted returning false");
            return false;
        }
    }

    
    @ProxyMethod("setMicrophoneMute")
    public static class SetMicrophoneMute extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: setMicrophoneMute called, forcing unmute");
            
            if (args != null && args.length > 0) {
                args[0] = false; 
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("startRecording")
    public static class StartRecording extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: startRecording called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("stopRecording")
    public static class StopRecording extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: stopRecording called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("isRecordingActive")
    public static class IsRecordingActive extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: isRecordingActive called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("getRecordingState")
    public static class GetRecordingState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: getRecordingState called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("isMicrophoneMutedForUser")
    public static class IsMicrophoneMutedForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: isMicrophoneMutedForUser returning false");
            return false;
        }
    }

    
    @ProxyMethod("setMicrophoneMuteForUser")
    public static class SetMicrophoneMuteForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: setMicrophoneMuteForUser called, forcing unmute");
            
            if (args != null && args.length > 1) {
                args[1] = false; 
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("getRecordingStateForUser")
    public static class GetRecordingStateForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: getRecordingStateForUser called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("requestAudioFocus")
    public static class RequestAudioFocus extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: requestAudioFocus called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("registerAudioFocusClient")
    public static class RegisterAudioFocusClient extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: registerAudioFocusClient called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("unregisterAudioFocusClient")
    public static class UnregisterAudioFocusClient extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: unregisterAudioFocusClient called, allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("abandonAudioFocus")
    public static class AbandonAudioFocus extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AudioService: abandonAudioFocus called, allowing");
            return method.invoke(who, args);
        }
    }
}
