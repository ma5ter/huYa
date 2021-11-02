package hu.yandex;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XCallback;

public class Forgery {
	private static final String TAG = "Forgery";
	private final ClassLoader classLoader;
	private final String[] recursivePrefixFilter;

	private final HashSet<String> baseMethods = new HashSet<String>();
	private final HashMap<Class<?>, Object> gags = new HashMap<Class<?>, Object>();

	public ClassLoader getClassLoader() {
		return classLoader;
	}

	final XC_MethodReplacement gagReplacement = new XC_MethodReplacement(XCallback.PRIORITY_LOWEST) {
		@Override
		protected Object replaceHookedMethod(MethodHookParam param) {
			Gag.logHooker(param);
			final Class<?> clazz = ((Method)param.method).getReturnType();
			Object instance = null;
			if (!gags.containsKey(clazz)) {
				try {
					instance = forge(clazz);
				}
				catch (IllegalAccessException e) {
					e.printStackTrace();
				}
				catch (InvocationTargetException e) {
					e.printStackTrace();
				}
				catch (InstantiationException e) {
					e.printStackTrace();
				}
				gags.put(clazz, instance);
			}
			else {
				instance = gags.get(clazz);
			}
			return instance;
		}
	};

	final XC_MethodReplacement baseReplacement = new XC_MethodReplacement(XCallback.PRIORITY_LOWEST) {
		private final Class<?> obectClass = Object.class;
		private final Class<?>[] emptyParams = new Class[]{};
		@Override
		protected Object replaceHookedMethod(MethodHookParam param) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
			Gag.logHooker(param);
			return obectClass.getMethod(param.method.getName(), emptyParams).invoke(param.thisObject);
		}
	};

	public Forgery(ClassLoader classLoader, String[] recursivePrefixFilter, Object[] defaultGags) {
		for (Method method : Object.class.getDeclaredMethods()) {
			baseMethods.add(method.getName());
		}
		this.classLoader = classLoader;
		this.recursivePrefixFilter = recursivePrefixFilter;
		if (null != defaultGags) {
			for (Object gag : defaultGags) {
				with(gag);
			}
		}
	}

	public Forgery with(@NonNull Object object) {
		gags.put(object.getClass(), object);
		return this;
	}

	public Forgery with(@NonNull Class<?> clazz, @NonNull Object object) {
		gags.put(clazz, object);
		return this;
	}

	public Object forge(@NonNull Class<?> clazz) throws IllegalAccessException, InvocationTargetException, InstantiationException {
		// try to find in already forged gags
		// also stop recursive processing for circular references when instance is under forge and set to null
		Log.d(TAG, "Forging: " + clazz.getCanonicalName());

		if (gags.containsKey(clazz)) {
			Log.d(TAG, "    found as forged");
			return gags.get(clazz);
		}

		if (clazz.isPrimitive()) {
			throw new RuntimeException("isPrimitive");
			// NOTE: it's possible to get the default value of any primitive type by creating an array
			// of one element and retrieving its first value
//			final Object value = Array.get(Array.newInstance(clazz, 1), 0);
//			Log.d(TAG, "    primitive value " + value);
//			gags.put(clazz, value);
//			return value;
		}

		if (clazz.equals(Object.class)) {
			throw new RuntimeException("isObject");
//			Log.d(TAG, "    object");
//			gags.put(Object.class, new Object());
		}

		// due to recursive nature avoid forging objects which have circular references
		// just put null for the class we are forging now
//		gags.put(clazz, null);

		// do constructors
		final Constructor<?>[] constructors = clazz.getDeclaredConstructors();
		if (constructors.length < 1) {
			throw new InstantiationException("Class without a constructor (i.e. interface) can't be instantiated");
		}

		// find a constructor with the minimum parameters count
		Constructor<?> simpleConstructor = null;
		Class<?>[] simpleParameterTypes = null;
		int count = Integer.MAX_VALUE;
		for (Constructor<?> constructor : constructors) {
			Log.d(TAG, "  constructor " + constructor);
			final Class<?>[] parameterTypes = constructor.getParameterTypes();
			if (count > parameterTypes.length) {
				count = parameterTypes.length;
				simpleConstructor = constructor;
				simpleParameterTypes = parameterTypes;
			}
			// also hook constructors to avoid any activity during instance creation
			XposedHelpers.findAndHookConstructor(clazz, Gag.getHookParamArgs(parameterTypes, Gag.nullReplacement));
		}

		// do methods
		final Method[] methods = clazz.getDeclaredMethods();
		for (Method method : methods) {
			Log.d(TAG, "  method " + method);
			final String methodName = method.getName();
			final Class<?> methodReturnType = method.getReturnType();
			final Class<?>[] methodParameterTypes = method.getParameterTypes();
			final int modifiers = method.getModifiers();

			if (!Modifier.isAbstract(modifiers) && !baseMethods.contains(methodName)) {
				final XC_MethodHook replacement = methodReturnType.equals(void.class) ? Gag.nullReplacement : gagReplacement;
				XposedHelpers.findAndHookMethod(clazz, methodName, Gag.getHookParamArgs(methodParameterTypes, replacement));
			}
		}

		// constructor parameters if needed
		assert simpleParameterTypes != null;
		final Object[] args = new Object[simpleParameterTypes.length];

		for (int i = 0; i < simpleParameterTypes.length; ++i) {
			args[i] = null;
//			final Class<?> parameterClass = simpleParameterTypes[i];
//			Object parameter = null;
//			// try to get already forged gag object
//			if (gags.containsKey(parameterClass)) {
//				parameter = gags.get(parameterClass);
//			}
//			else {
//				// when not found try to forge one
//				if (null != recursivePrefixFilter) {
//					final String parameterClassName = parameterClass.getCanonicalName();
//					for (String prefix : recursivePrefixFilter) {
//						if (parameterClassName.startsWith(prefix)) {
//							parameter = forge(parameterClass);
//							gags.put(parameterClass, parameter);
//							break;
//						}
//					}
//				}
//			}
//			args[i] = parameter;
		}

		final Object instance = simpleConstructor.newInstance(args);

//		gags.remove(clazz);
		gags.put(clazz, instance);

		Log.d(TAG, "  forged " + clazz.getCanonicalName());
		return instance;
	}

	public Object forge(String name) throws ClassNotFoundException, InvocationTargetException, InstantiationException, IllegalAccessException {
		final Class<?> clazz = classLoader.loadClass(name);
		return forge(clazz);
	}

}
