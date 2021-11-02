package hu.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XCallback;

public class Gag {
	final static XC_MethodReplacement nullReplacement = new XC_MethodReplacement(XCallback.PRIORITY_LOWEST) {
		@Nullable
		@Override
		protected Object replaceHookedMethod(MethodHookParam param) {
			logHooker(param);
			return null;
		}
	};

	@NonNull
	public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
		try {
			XC_MethodHook.Unhook unhook = XposedHelpers.findAndHookMethod(clazz, methodName, parameterTypesAndCallback);
			XposedBridge.log("=== Hooker for " + unhook.getHookedMethod());
			return unhook;
		}
		catch (NoSuchMethodError e) {
			final StringBuilder builder = new StringBuilder();
			boolean hasParams = false;
			builder.append("=== No hooker for ").append(clazz.getCanonicalName()).append('.').append(methodName).append('(');
			for (final Object arg : parameterTypesAndCallback) {
				if (arg instanceof Class) {
					if (hasParams) builder.append(",");
					builder.append(((Class<?>) arg).getCanonicalName());
					hasParams = true;
				}
			}
			builder.append(')');
			XposedBridge.log(builder.toString());
			return null;
		}
	}

	static void logHooker(@NonNull XC_MethodHook.MethodHookParam param) {
		final StringBuilder builder = new StringBuilder();
		boolean hasParams = false;
		builder.append("=== Called ").append(param.method).append(" (");
		for (final Object arg : param.args) {
			if (hasParams) builder.append(",");
			builder.append(arg);
			hasParams = true;
		}
		builder.append(')');
		final Object result = param.getResult();
		if (result != null) {
			builder.append(" -> ").append(result);
		}
		XposedBridge.log(builder.toString());
	}

	@NonNull
	static Object[] getHookParamArgs(@NonNull Class<?>[] classes, XC_MethodHook replacement) {
		final Object[] args = new Object[classes.length + 1];
		System.arraycopy(classes, 0, args, 0, classes.length);
		args[classes.length] = replacement;
		return args;
	}

	@NonNull
	static Object[] getHookParamArgs(@NonNull Method method, XC_MethodHook replacement) {
		return getHookParamArgs(method.getParameterTypes(), replacement);
	}

	@NonNull
	static Object[] getHookParamArgs(@NonNull Constructor<?> constructor, XC_MethodHook replacement) {
		return getHookParamArgs(constructor.getParameterTypes(), replacement);
	}

	@NonNull
	private  static Object forgeInstance(@NonNull Class<?> clazz, @NonNull String recursivePrefixFilter) {
		XposedBridge.log("=== Forging: " + clazz.getCanonicalName());

		// hook all constructors first
		final Constructor<?>[] constructors = clazz.getDeclaredConstructors();
		if (constructors.length < 1) return null;

		boolean shouldHook = false;
		if (null == recursivePrefixFilter || Objects.requireNonNull(clazz.getCanonicalName()).startsWith(recursivePrefixFilter)) {
			if (null == XposedHelpers.getAdditionalStaticField(clazz, "alreadyHooked")) {
				shouldHook = true;
			}
		}

		XposedBridge.log("===   should hook: " + shouldHook);

		Constructor<?> simpleConstructor = null;
		Class<?>[] simpleParameterTypes = null;
		int count = Integer.MAX_VALUE;
		for (Constructor<?> constructor : constructors) {
			XposedBridge.log("===   constructor: " + constructor);
			final Class<?>[] parameterTypes = constructor.getParameterTypes();
			// publicize constructors
			if (!constructor.isAccessible()) {
				constructor.setAccessible(true);
			}
			// hook gag replacement
			if (shouldHook) {
				XposedHelpers.findAndHookConstructor(clazz, getHookParamArgs(parameterTypes, nullReplacement));
			}
			if (count > parameterTypes.length) {
				count = parameterTypes.length;
				simpleConstructor = constructor;
				simpleParameterTypes = parameterTypes;
			}
		}
		XposedHelpers.setAdditionalStaticField(clazz, "alreadyHooked", true);

		// call constructor with less parameters count
		final Object[] args = new Object[simpleParameterTypes.length];
		for (int i = 0; i < simpleParameterTypes.length; ++i) {
			// when recursivePrefixFilter is not null will forge all parameters objects otherwise pass nulls
			args[i] = (recursivePrefixFilter == null) ? null : forgeInstance(simpleParameterTypes[i], recursivePrefixFilter);
		}

		try {
			final Object instance = simpleConstructor.newInstance(args);
			XposedBridge.log("===   forged: " + instance);
			return instance;
		}
		catch (Exception e) {
			XposedBridge.log("===   null forged: " + clazz.getCanonicalName() + " (" + e.getMessage() + ")");
			return null;
		}
	}

	public static void doSingleton(@NonNull Class<?> clazz, XC_MethodHook replacement) {
		final Method[] methods = clazz.getDeclaredMethods();
		for (Method method: methods) {
			// typical static getInstance() return an instance of a class
			final int modifiers = method.getModifiers();
			if (Modifier.isStatic(modifiers) && method.getReturnType().equals(clazz)) {
				findAndHookMethod(clazz, method.getName(), getHookParamArgs(method.getParameterTypes(), replacement));
			}
		}
	}

}
