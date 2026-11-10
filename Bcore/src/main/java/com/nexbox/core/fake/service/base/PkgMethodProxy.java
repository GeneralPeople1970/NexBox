package com.nexbox.core.fake.service.base;

import java.lang.reflect.Method;

import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.utils.MethodParameterUtils;

public class PkgMethodProxy extends MethodHook {

	String mName;

	public PkgMethodProxy(String name) {
		mName = name;
	}

	@Override
	protected String getMethodName() {
		return mName;
	}

	@Override
	protected Object hook(Object who, Method method, Object[] args) throws Throwable {
		MethodParameterUtils.replaceFirstAppPkg(args);
		return method.invoke(who, args);
	}
}
