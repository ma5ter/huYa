package hu.yandex;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Module implements IXposedHookLoadPackage {
	// gag replacement
	private final static XC_MethodReplacement voidMethodGagReplacement = new XC_MethodReplacement() {
		@Nullable
		@Override
		protected Object replaceHookedMethod(MethodHookParam param) {
			logHooker(param);
			return null;
		}
	};

	private final static XC_MethodReplacement constructorGagReplacement = new XC_MethodReplacement() {
		@Nullable
		@Override
		protected Object replaceHookedMethod(MethodHookParam param) {
			logHooker(param);
			return null;
		}
	};

	@Nullable
	private static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
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

	private static void logHooker(@NonNull XC_MethodHook.MethodHookParam param) {
		final StringBuilder builder = new StringBuilder();
		boolean hasParams = false;
		builder.append("=== Called ").append(param.method.getName()).append('(');
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
	private static Object[] getHookParamArgs(@NonNull Class<?>[] classes, XC_MethodHook replacement) {
		final Object[] args = new Object[classes.length + 1];
		System.arraycopy(classes, 0, args, 0, classes.length);
		args[classes.length] = replacement;
		return args;
	}

	@NonNull
	private static Object[] getHookParamArgs(@NonNull Method method, XC_MethodHook replacement) {
		return getHookParamArgs(method.getParameterTypes(), replacement);
	}

	@NonNull
	private static Object[] getHookParamArgs(@NonNull Constructor<?> constructor, XC_MethodHook replacement) {
		return getHookParamArgs(constructor.getParameterTypes(), replacement);
	}

	private static void gagVoidMethods(@NonNull Class<?> clazz, String... exclude) {
		final Method[] metricaMethods = clazz.getDeclaredMethods();
		for (Method method : metricaMethods) {
			if (void.class == method.getReturnType()) {
				final String name = method.getName();
				if (!Arrays.asList(exclude).contains(name)) {
					findAndHookMethod(clazz, name, getHookParamArgs(method, voidMethodGagReplacement));
				}
			}
		}
	}

	@NonNull
	private static Object forgeGagObject(@NonNull Class<?> clazz, @NonNull String recursivePrefixFilter) {
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
				XposedHelpers.findAndHookConstructor(clazz, getHookParamArgs(parameterTypes, constructorGagReplacement));
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
			args[i] = (recursivePrefixFilter == null) ? null : forgeGagObject(simpleParameterTypes[i], recursivePrefixFilter);
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

	private static void doMetrica(@NonNull final ClassLoader classLoader) {
		final Class<?> metricaClass;
		try {
			metricaClass = classLoader.loadClass("com.yandex.metrica.YandexMetrica");
		}
		catch (ClassNotFoundException e) {
			// means no metrica in this app
			return;
		}
		XposedBridge.log("=== Found: " + metricaClass.getCanonicalName());

		// Mock IReporter
		Object reporter = null;
		try {
			// NOTE: if you load the interface X in "your" class loader, and then get an object that appears to implement
			// class X from a "foreign" class loader, a cast to "your" X will fail.
			// To make it work, X has to be loaded from a class loader that is a common parent to the other two loaders.
			final Class<?> iReporterClass = classLoader.loadClass("com.yandex.metrica.IReporter");

            /*
            final Class<?> proxy = classLoader.loadClass("java.lang.reflect.Proxy");
            XposedBridge.log("=== Proxy: " + proxy.getCanonicalName());
            final Class<?>[] classes = new Class[3];
            classes[0] = ClassLoader.class;
            classes[1] = classes.getClass();
            classes[2] = InvocationHandler.class;
            final Method proxyInstance = proxy.getMethod("newProxyInstance", classes);
            XposedBridge.log("=== newProxyInstance: " + proxyInstance);
            final Object reporter = proxyInstance.invoke(null, classLoader, new Class<?>[]{iReporterClass},
            */

			reporter = Proxy.newProxyInstance(classLoader, new Class<?>[]{iReporterClass},
				(InvocationHandler) (proxy1, method, args) -> {
					if ("hashCode".equals(method.getName())) {
						return proxy1.hashCode();
					}
					else if ("equals".equals(method.getName())) {
						return proxy1.equals(args[0]);
					}
					else if ("toString".equals(method.getName())) {
						return "com.yandex.metrica.IReporter";
					}
					else {
						XposedBridge.log("=== IReporter.invoke: " + method.getName());
						return null;
					}
				});
			XposedBridge.log("=== Forged IReporter: " + reporter);
		}
		catch (ClassNotFoundException e) {
			XposedBridge.log("=== IReporter interface not found or forged!");
		}

		findAndHookMethod(metricaClass, "getLibraryApiLevel",
			new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					logHooker(param);
				}
			});

		findAndHookMethod(metricaClass, "getLibraryVersion",
			new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					logHooker(param);
				}
			});

		final Object finalReporter = reporter;
		findAndHookMethod(metricaClass, "getReporter",
			Context.class, String.class,
			new XC_MethodReplacement() {
				@Override
				protected Object replaceHookedMethod(MethodHookParam param) {
					XposedBridge.log("=== Called getReporter() -> " + finalReporter);
					return finalReporter;
				}
			}

			/*
			new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					Object reporter = param.getResult();
					if (reporter != null) {
						Class<?> reporterClass = reporter.getClass();
						XposedBridge.log("=== YandexMetrica.getReporter() -> " + reporterClass.getCanonicalName());

						// hook all the IReporter methods
						findAndHookMethod(reporterClass, "sendEventsBuffer",
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log("=== IReporter.sendEventsBuffer()");
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportEvent",
								String.class,
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log(String.format("=== IReporter.reportEvent(\"%s\")", (String) param.args[0]));
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportEvent",
								String.class, String.class,
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										final String s1 = (String) param.args[1];
										if (s1 != null) {
											XposedBridge.log(String.format("=== IReporter.reportEvent(\"%s\", \"%s\")", (String) param.args[0], s1));
										}
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportEvent",
								String.class, Map.class,
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										final String s = (String) param.args[0];
										final Map<String, Object> map = (Map<String, Object>) param.args[1];
										String s1;
										if (map != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
											s1 = map.entrySet()
													.stream()
													.map(e -> e.getKey() + "=" + e.getValue())
													.collect(joining("&"));
										} else {
											s1 = "Map...";
										}
										XposedBridge.log(String.format("=== IReporter.reportEvent(\"%s\", \"%s\")", s, s1));
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportError",
								String.class, Throwable.class,
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										final Throwable throwable = (Throwable) param.args[1];
										if (throwable != null) {
											XposedBridge.log(String.format("=== IReporter.reportError(\"%s\", \"%s\")", param.args[0],
													throwable.toString()));
										}
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportError",
								String.class, String.class,
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										final String s1 = (String) param.args[1];
										if (s1 != null) {
											XposedBridge.log(String.format("=== IReporter.reportError(\"%s\", \"%s\")", param.args[0], s1));
										}
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportError",
								String.class, String.class, Throwable.class,
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										final Throwable throwable = (Throwable) param.args[2];
										String s1 = (String) param.args[1];
										if (s1 == null) {
											s1 = "";
										}
										if (throwable != null) {
											XposedBridge.log(String.format("=== IReporter.reportError(\"%s\", \"%s\", \"%s\")", param.args[0], s1,
													throwable.toString()));
										}
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportUnhandledException",
								Throwable.class,
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log(String.format("=== IReporter.reportUnhandledException(\"%s\")",
												((Throwable) param.args[0]).toString()));
										return null;
									}
								});
						findAndHookMethod(reporterClass, "resumeSession",
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log("=== IReporter.resumeSession()");
										return null;
									}
								});
						findAndHookMethod(reporterClass, "pauseSession",
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log("=== IReporter.pauseSession()");
										return null;
									}
								});
						findAndHookMethod(reporterClass, "setUserProfileID",
								String.class,
								new XC_MethodHook() {
									@Override
									protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
										Object s = param.args[0];
										if (s == null) {
											s = "(null)";
										}
										XposedBridge.log(String.format("=== IReporter.setUserProfileID(\"%s\")", s));
										param.args[0] = null;
									}
								});
						findAndHookMethod(reporterClass, "reportUserProfile",
								"com.yandex.metrica.profile.UserProfile",
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log(String.format("=== IReporter.reportUserProfile(%s)", param.args[0].toString()));
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportRevenue",
								"com.yandex.metrica.Revenue",
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log(String.format("=== IReporter.reportRevenue(%s)", param.args[0].toString()));
										return null;
									}
								});
						findAndHookMethod(reporterClass, "reportECommerce",
								"com.yandex.metrica.ecommerce.ECommerceEvent",
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log(String.format("=== IReporter.reportECommerce(%s)", param.args[0].toString()));
										return null;
									}
								});
						findAndHookMethod(reporterClass, "setStatisticsSending",
								Boolean.class,
								new XC_MethodReplacement() {
									@Override
									protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
										XposedBridge.log(String.format("=== IReporter.setStatisticsSending(%s)", Boolean.toString((Boolean) param.args[0])));
										return null;
									}
								});
					}
				}
			}
			*/
		);

		findAndHookMethod(metricaClass, "requestAppMetricaDeviceID",
			"com.yandex.metrica.AppMetricaDeviceIDListener",
			new XC_MethodReplacement() {
				@Override
				protected Object replaceHookedMethod(MethodHookParam param) {
					logHooker(param);
					final Object listener = param.args[0];
					if (listener != null) {
						new Thread(() -> {
							final Class<?> listenerClass = listener.getClass();
							try {
								final Method onLoaded = listenerClass.getMethod("onLoaded", String.class);
								// change device id every week
								final Random random = new Random(new Date().getTime() / 1000 / 60 / 60 / 24 / 7);
								final String id = Long.toString(Math.abs(random.nextLong()));
								onLoaded.invoke(listener, id);
								XposedBridge.log("===   Forged device ID: " + id);
							}
							catch (Exception e) {
								Log.d("Reflection", e.getMessage());
							}
						}).start();
					}
					return null;
				}
			}
		);

		findAndHookMethod(metricaClass, "activate",
			Context.class, "com.yandex.metrica.YandexMetricaConfig",
			new XC_MethodReplacement() {
				@Override
				protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
					logHooker(param);
					final Reflector metricaConfig = new Reflector("com.yandex.metrica.YandexMetricaConfig", classLoader, param.args[1]);
					final String apiKey = (String) metricaConfig.getField("apiKey");
					XposedBridge.log("===   Config.apiKey: " + apiKey);
					return null;
				}
			}

			/*
			new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					logHooker(param);
					final Reflector oldConfig = new Reflector("com.yandex.metrica.YandexMetricaConfig", classLoader, param.args[1]);

					final String apiKey = (String) oldConfig.getField("apiKey");
					final String appVersion = (String) oldConfig.getField("appVersion");

					XposedBridge.log("===   Config.apiKey: " + apiKey);
					XposedBridge.log("===   Config.appOpenTrackingEnabled: " + oldConfig.getField("appOpenTrackingEnabled"));
					XposedBridge.log("===   Config.appVersion: " + appVersion);
					XposedBridge.log("===   Config.crashReporting: " + oldConfig.getField("crashReporting"));
					XposedBridge.log("===   Config.firstActivationAsUpdate: " + oldConfig.getField("firstActivationAsUpdate"));
					XposedBridge.log("===   Config.installedAppCollecting: " + oldConfig.getField("installedAppCollecting"));
					XposedBridge.log("===   Config.location: " + oldConfig.getField("location"));
					XposedBridge.log("===   Config.locationTracking: " + oldConfig.getField("locationTracking"));
					XposedBridge.log("===   Config.logs: " + oldConfig.getField("logs"));
					XposedBridge.log("===   Config.maxReportsInDatabaseCount: " + oldConfig.getField("maxReportsInDatabaseCount"));
					XposedBridge.log("===   Config.nativeCrashReporting: " + oldConfig.getField("nativeCrashReporting"));
					XposedBridge.log("===   Config.preloadInfo: " + oldConfig.getField("preloadInfo"));
					XposedBridge.log("===   Config.sessionsAutoTrackingEnabled: " + oldConfig.getField("sessionsAutoTrackingEnabled"));
					XposedBridge.log("===   Config.revenueAutoTrackingEnabled: " + oldConfig.getField("revenueAutoTrackingEnabled"));
					XposedBridge.log("===   Config.sessionTimeout: " + oldConfig.getField("sessionTimeout"));
					XposedBridge.log("===   Config.statisticsSending: " + oldConfig.getField("statisticsSending"));
					XposedBridge.log("===   Config.userProfileID: " + oldConfig.getField("userProfileID"));

					final Object configBuilderObject = oldConfig.invoke("newConfigBuilder", apiKey);
					XposedBridge.log("===   Config.Builder: " + configBuilderObject);

					if (configBuilderObject != null) {
						final Reflector configBuilder = new Reflector("com.yandex.metrica.YandexMetricaConfig$Builder", classLoader, configBuilderObject);
						// configBuilder.logMethods();

						configBuilder.invoke("withLogs");
						configBuilder.invoke("withAppVersion", appVersion);

						configBuilder.invoke("withAppOpenTrackingEnabled", false);
						configBuilder.invoke("withCrashReporting", false);
						configBuilder.invoke("withErrorEnvironmentValue", false);
						configBuilder.invoke("withInstalledAppCollecting", false);
						configBuilder.invoke("withLocationTracking", false);
						configBuilder.invoke("withMaxReportsInDatabaseCount", 1);
						configBuilder.invoke("withNativeCrashReporting", false);
						configBuilder.invoke("withRevenueAutoTrackingEnabled", false);
						configBuilder.invoke("withSessionsAutoTrackingEnabled", false);
						configBuilder.invoke("withSessionTimeout", Integer.MAX_VALUE);
						configBuilder.invoke("withStatisticsSending", false);

						final Object newConfigObject = configBuilder.invoke("build");

						param.args[1] = newConfigObject;
					}
				}

				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					logHooker(param);
				}
			}
			*/
		);

		gagVoidMethods(metricaClass, "activate", "requestAppMetricaDeviceID");
	}

	private static void doAnalytics(@NonNull final ClassLoader classLoader) {
		final Class<?> analyticsClass;
		try {
			analyticsClass = classLoader.loadClass("com.google.firebase.analytics.FirebaseAnalytics");
		}
		catch (ClassNotFoundException e) {
			// means not included in app
			return;
		}
		XposedBridge.log("=== Found: " + analyticsClass.getCanonicalName());

		final Reflector analytics = new Reflector(analyticsClass, null);
		analytics.logMethods();

		// Mock FirebaseAnalytics
		Object instance = forgeGagObject(analyticsClass, "com.google.");

		findAndHookMethod(analyticsClass, "getInstance",
			Context.class,
			new XC_MethodReplacement() {
				@Override
				protected Object replaceHookedMethod(MethodHookParam param) {
					logHooker(param);
					return instance;
				}
			});

		gagVoidMethods(analyticsClass);


		XposedHelpers.findAndHookMethod("android.app.ActivityThread", classLoader, "installProvider",
			Context.class, "android.app.ContentProviderHolder", "android.content.pm.ProviderInfo", boolean.class, boolean.class, boolean.class,
			new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) {
					logHooker(param);
					// return passed android.app.ContentProviderHolder
					param.setResult(param.args[1]);


					try {
						final Reflector r = new Reflector("android.content.pm.ProviderInfo", classLoader, param.args[2]);
						r.logMethods();
						// TODO:
						r.logFields();
						r.logValues();
					}
					catch (ClassNotFoundException e) {
						e.printStackTrace();
					}

				}
			});

	}

	private static void doCrashlytics(@NonNull final ClassLoader classLoader) {
		// NOTE: com.google.firebase.crashlytics.FirebaseCrashlytics class is obfuscated
		final Class<?> crashlyticsClass;
		try {
			crashlyticsClass = classLoader.loadClass("com.google.firebase.crashlytics.a");
		}
		catch (ClassNotFoundException e) {
			e.printStackTrace();
			return;
		}
		XposedBridge.log("=== Found: " + crashlyticsClass.getCanonicalName());

		final Reflector crashlytics = new Reflector(crashlyticsClass, null);
		crashlytics.logMethods();

		findAndHookMethod(crashlyticsClass, "getLibraryApiLevel",
			new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					logHooker(param);
				}
			});

		gagVoidMethods(crashlyticsClass);
	}

	@Override
	public void handleLoadPackage(@NonNull XC_LoadPackage.LoadPackageParam lpparam) {
		XposedBridge.log("=== Loaded app: " + lpparam.packageName);
		final ClassLoader classLoader = lpparam.classLoader;

		doMetrica(classLoader);
		doAnalytics(classLoader);
		doCrashlytics(classLoader);

		// com.google.firebase.FirebaseOptions
		// com.yandex.metrica.MetricaService

		XposedBridge.log("===");
	}
}
