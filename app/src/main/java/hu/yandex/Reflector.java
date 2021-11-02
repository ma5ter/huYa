package hu.yandex;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

public class Reflector {
    private final String TAG = "Reflector";
    private final Class<?> clazz;
    private Object instance;

    public boolean log = true;

    public Reflector(Class<?> clazz, Object instance) {
        this.instance = instance;
        this.clazz = clazz;
    }

    public Reflector(String className, ClassLoader classLoader, Object instance) throws ClassNotFoundException {
    	this(classLoader.loadClass(className), instance);
    }

    public static Class<?>[] getClasses(Object... args) {
        final Class<?>[] classes = new Class[args.length];
        for (int i = 0; i < args.length; ++i) {
            Class<?> argClass = args[i].getClass();
            if (argClass == Boolean.class) {
                argClass = boolean.class;
            } else if (argClass == Integer.class) {
                argClass = int.class;
            } else if (argClass == Float.class) {
                argClass = float.class;
            } else if (argClass == Double.class) {
                argClass = double.class;
            }
            classes[i] = argClass;
        }
        return classes;
    }

    public void setInstance(Object instance) {
        this.instance = instance;
    }

    public Object getField(String name) {
        try {
            return clazz.getField(name).get(instance);
        } catch (IllegalAccessException e) {
            if (log) Log.d(TAG, "getField(" + name + "): IllegalAccessException");
        } catch (NoSuchFieldException e) {
            if (log) Log.d(TAG, "getField(" + name + "): NoSuchFieldException");
        }
        return null;
    }

    public void setField(String name, Object value) {
        try {
            clazz.getField(name).set(instance, value);
        } catch (IllegalAccessException e) {
            if (log) Log.d(TAG, "setField(" + name + "): IllegalAccessException");
        } catch (NoSuchFieldException e) {
            if (log) Log.d(TAG, "setField(" + name + "): NoSuchFieldException");
        }
    }

    public Object invoke(String name, Object... args) {
        final Class<?>[] classes = getClasses(args);
        try {
            return clazz.getMethod(name, classes).invoke(instance, args);
        } catch (IllegalAccessException e) {
            if (log) Log.d(TAG, "invoke(" + name + "): IllegalAccessException");
        } catch (InvocationTargetException e) {
            if (log) Log.d(TAG, "invoke(" + name + "): InvocationTargetException");
        } catch (NoSuchMethodException e) {
            if (log) Log.d(TAG, "invoke(" + name + "): NoSuchMethodException");
        }
        return null;
    }

    public void logMethods() {
        if (log) {
            Log.d(TAG, "Methods of " + clazz.getCanonicalName());
            final Method[] methods = clazz.getDeclaredMethods();
            for (Method method : methods) {
                Log.d(TAG, "  " + method);
            }
        }
    }

	public void logFields() {
		if (log) {
			Log.d(TAG, "Fields of " + clazz.getCanonicalName());
			final Field[] fields = clazz.getDeclaredFields();
			for (Field field : fields) {
				Log.d(TAG, "  " + field);
			}
		}
	}

	public void logValues() {
		if (log && instance != null) {
			Log.d(TAG, "Fields of " + instance);
			final Field[] fields = clazz.getDeclaredFields();
			for (Field field : fields) {
				try {
					Log.d(TAG, "  " + field + " = " + field.get(instance));
				}
				catch (IllegalAccessException e) {
					Log.d(TAG, "  " + field + " <no access>");
				}
			}
		}
	}
}
