package luceedebug.extension;

import java.lang.reflect.Method;

import lucee.runtime.config.Config;

import luceedebug.DapServer;
import luceedebug.EnvUtil;
import luceedebug.Log;
import luceedebug.coreinject.NativeLuceeVm;

/**
 * Extension startup hook - instantiated by Lucee when the extension loads.
 * Uses Lucee's startup-hook mechanism (manifest attribute).
 *
 * Native-only mode: requires Lucee 7.1+ with DebuggerRegistry API.
 * No JDWP, no Java agent, no bytecode instrumentation.
 */
public class ExtensionActivator {
	private static NativeLuceeVm luceeVm;
	private static ClassLoader luceeLoader;
	private static ClassLoader extensionLoader;
	private static boolean listenerRegistered = false;
	private static boolean alreadyActivated = false;
	// Keep a static reference to prevent GC from collecting the instance
	// (Lucee's startup-hook discards its reference immediately)
	private static ExtensionActivator instance;

	/**
	 * Constructor called by Lucee's startup-hook mechanism.
	 * Lucee passes the Config object automatically.
	 * May be called multiple times (ConfigServer + each ConfigWeb).
	 */
	public ExtensionActivator(Config luceeConfig) {
		// Only activate once
		if (alreadyActivated) {
			return;
		}
		alreadyActivated = true;
		// Keep a reference to prevent GC
		instance = this;

		try {
			// Get debug port - if not set, debugger is disabled
			int debugPort = EnvUtil.getDebuggerPort();
			if (debugPort < 0) {
				Log.info("Debugger disabled - set LUCEE_DAP_SECRET to enable");
				return;
			}
			Log.info("Extension activating");

			// Store classloaders for later listener registration
			extensionLoader = this.getClass().getClassLoader();
			luceeLoader = luceeConfig.getClass().getClassLoader();

			// Determine filesystem case sensitivity from Lucee's config location
			String configPath = luceeConfig.getConfigDir().getAbsolutePath();
			boolean fsCaseSensitive = luceedebug.Config.checkIfFileSystemIsCaseSensitive(configPath);

			// Create luceedebug config
			luceedebug.Config config = new luceedebug.Config(fsCaseSensitive);

			// Set Lucee classloader for reflection access to core classes
			NativeLuceeVm.setLuceeClassLoader(luceeLoader);

			// Create NativeLuceeVm
			luceeVm = new NativeLuceeVm(config);

			// Start DAP server in background thread (createForSocket blocks forever)
			// Listener registration is deferred until DAP client connects with secret
			final int port = debugPort;
			final luceedebug.Config finalConfig = config;
		Thread dapThread = new Thread(() -> {
			// Use System.out directly to ensure we see output even if Log class has issues
			System.out.println("[luceedebug] DAP server thread started");
			System.out.flush();
			try {
				System.out.println("[luceedebug] Calling DapServer.createForSocket on port " + port);
				System.out.flush();
				DapServer.createForSocket(luceeVm, finalConfig, "localhost", port);
				// This line should never be reached - createForSocket loops forever
				System.out.println("[luceedebug] DAP server createForSocket returned unexpectedly");
			} catch (Throwable t) {
				System.out.println("[luceedebug] DAP server thread failed: " + t.getClass().getName() + ": " + t.getMessage());
				t.printStackTrace(System.out);
				Log.error("DAP server thread failed", t);
			}
			System.out.println("[luceedebug] DAP server thread exiting");
		}, "luceedebug-dap-server");
		dapThread.setDaemon(true);
		dapThread.setUncaughtExceptionHandler((t, e) -> {
			System.out.println("[luceedebug] DAP thread died with uncaught exception: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace(System.out);
		});
		dapThread.start();

		Log.info("Native mode, DAP server starting on localhost:" + debugPort);
		} catch (Throwable t) {
			System.out.println("[luceedebug] Extension activation failed: " + t.getClass().getName() + ": " + t.getMessage());
			t.printStackTrace(System.out);
			Log.error("Extension activation failed", t);
		}
	}

	/**
	 * Register the debugger listener with Lucee using the client-provided secret.
	 * Called from DapServer.attach() when client connects.
	 * Secret is validated on every connection, not just the first one.
	 *
	 * @param secret The secret from launch.json
	 * @return true if registration succeeded
	 */
	public static synchronized boolean registerListener(String secret) {
		if (luceeLoader == null || extensionLoader == null) {
			Log.error("Cannot register listener - extension not initialized");
			return false;
		}
		if (secret == null || secret.trim().isEmpty()) {
			Log.error("Cannot register listener - no secret provided");
			return false;
		}
		// Always validate secret, even if already registered
		String expectedSecret = EnvUtil.getDebuggerSecret();
		if (expectedSecret == null || !expectedSecret.equals(secret.trim())) {
			Log.error("Invalid secret");
			return false;
		}
		// Only register with Lucee once
		if (!listenerRegistered) {
			if (registerNativeDebuggerListener(luceeLoader, extensionLoader, secret.trim())) {
				listenerRegistered = true;
			} else {
				return false;
			}
		}
		return true;
	}

	/**
	 * Check if listener is already registered.
	 */
	public static boolean isListenerRegistered() {
		return listenerRegistered;
	}

	/**
	 * Check if the extension was actually activated by Lucee (native mode).
	 * In agent mode, the class exists but wasn't activated via startup-hook.
	 */
	public static boolean isNativeModeActive() {
		return alreadyActivated;
	}

	/**
	 * Enable DebuggerExecutionLog via ConfigAdmin.
	 * This triggers template recompilation with exeLogStart()/exeLogEnd() bytecode
	 * which calls DebuggerRegistry.shouldSuspend() on each line.
	 *
	 * Note: During startup-hook, we receive ConfigServer (not ConfigWeb).
	 * We need to find a ConfigAdmin.newInstance() method that works with ConfigServer.
	 */
	private void enableDebuggerExecutionLog(Config luceeConfig, ClassLoader luceeLoader) {
		try {
			// Load ConfigAdmin class from Lucee core
			Class<?> configAdminClass = luceeLoader.loadClass("lucee.runtime.config.ConfigAdmin");
			Class<?> classDefClass = luceeLoader.loadClass("lucee.runtime.db.ClassDefinition");
			Class<?> classDefImplClass = luceeLoader.loadClass("lucee.transformer.library.ClassDefinitionImpl");
			Class<?> structClass = luceeLoader.loadClass("lucee.runtime.type.Struct");
			Class<?> structImplClass = luceeLoader.loadClass("lucee.runtime.type.StructImpl");

			// Find the right newInstance method - try different signatures
			Object configAdmin = null;

			// Try to find a method that accepts our config type
			for (Method m : configAdminClass.getMethods()) {
				if (m.getName().equals("newInstance") && m.getParameterCount() >= 2) {
					Class<?>[] params = m.getParameterTypes();
					// Look for (Config/ConfigServer, String/Password, boolean) or similar
					if (params[0].isAssignableFrom(luceeConfig.getClass())) {
						try {
							if (params.length == 2) {
								// (Config, Password)
								configAdmin = m.invoke(null, luceeConfig, null);
							} else if (params.length == 3 && params[2] == boolean.class) {
								// (Config, Password, optionalPW)
								configAdmin = m.invoke(null, luceeConfig, null, true);
							}
							if (configAdmin != null) {
								Log.info("Created ConfigAdmin using " + m);
								break;
							}
						} catch (Exception e) {
							// Try next method
						}
					}
				}
			}

			if (configAdmin == null) {
				Log.error("Could not create ConfigAdmin - no compatible newInstance method found");
				Log.info("Available newInstance methods:");
				for (Method m : configAdminClass.getMethods()) {
					if (m.getName().equals("newInstance")) {
						Log.info("  " + m);
					}
				}
				return;
			}

			// Create ClassDefinition for DebuggerExecutionLog
			java.lang.reflect.Constructor<?> cdConstructor = classDefImplClass.getConstructor(String.class);
			Object classDefinition = cdConstructor.newInstance("lucee.runtime.engine.DebuggerExecutionLog");

			// Create empty Struct for arguments
			Object emptyStruct = structImplClass.getConstructor().newInstance();

			// admin.updateExecutionLog(classDefinition, arguments, enabled=true)
			Method updateMethod = configAdminClass.getMethod("updateExecutionLog",
				classDefClass, structClass, boolean.class);
			updateMethod.invoke(configAdmin, classDefinition, emptyStruct, true);

			// Persist and reload config - this triggers template recompilation
			Method storeMethod = configAdminClass.getMethod("storeAndReload");
			storeMethod.invoke(configAdmin);

			Log.info("Enabled DebuggerExecutionLog - templates will recompile with debugger bytecode");
		} catch (ClassNotFoundException e) {
			Log.error("ConfigAdmin not found - cannot enable execution log: " + e.getMessage());
		} catch (Throwable e) {
			Log.error("Failed to enable execution log", e);
		}
	}

	/**
	 * Register native debugger listener using cross-classloader proxy.
	 * DebuggerRegistry and DebuggerListener are in Lucee's core (luceeLoader).
	 * NativeDebuggerListener is in our extension bundle (extensionLoader).
	 * Requires the correct secret to register.
	 */
	private static boolean registerNativeDebuggerListener(ClassLoader luceeLoader, ClassLoader extensionLoader, String secret) {
		try {
			// Load Lucee core classes
			Class<?> registryClass = luceeLoader.loadClass("lucee.runtime.debug.DebuggerRegistry");
			Class<?> listenerInterface = luceeLoader.loadClass("lucee.runtime.debug.DebuggerListener");
			Class<?> pageContextClass = luceeLoader.loadClass("lucee.runtime.PageContext");

			// Load our implementation from extension bundle
			Class<?> nativeListenerClass = extensionLoader.loadClass(
				"luceedebug.coreinject.NativeDebuggerListener");

			// Cache method lookups
			final Method getNameMethod = nativeListenerClass.getMethod("getName");
			final Method onSuspendMethod = nativeListenerClass.getMethod("onSuspend",
				pageContextClass, String.class, int.class, String.class);
			final Method onResumeMethod = nativeListenerClass.getMethod("onResume", pageContextClass);
			final Method shouldSuspendMethod = nativeListenerClass.getMethod("shouldSuspend",
				pageContextClass, String.class, int.class);
			final Method isDapClientConnectedMethod = nativeListenerClass.getMethod("isDapClientConnected");
			final Method onExceptionMethod = nativeListenerClass.getMethod("onException",
				pageContextClass, Throwable.class, boolean.class);
			final Method onOutputMethod = nativeListenerClass.getMethod("onOutput",
				String.class, boolean.class);
			final Method onFunctionEntryMethod = nativeListenerClass.getMethod("onFunctionEntry",
				pageContextClass, String.class, String.class, String.class, int.class);

			// Create proxy in Lucee's classloader, delegating to extension's implementation
			Object listenerProxy = java.lang.reflect.Proxy.newProxyInstance(
				luceeLoader,
				new Class<?>[] { listenerInterface },
				(proxy, method, args) -> {
					try {
						switch (method.getName()) {
							case "getName": return getNameMethod.invoke(null);
							case "isClientConnected": return isDapClientConnectedMethod.invoke(null);
							case "onSuspend": return onSuspendMethod.invoke(null, args);
							case "onResume": return onResumeMethod.invoke(null, args);
							case "shouldSuspend": return shouldSuspendMethod.invoke(null, args);
							case "onException": return onExceptionMethod.invoke(null, args);
							case "onOutput": return onOutputMethod.invoke(null, args);
							case "onFunctionEntry": return onFunctionEntryMethod.invoke(null, args);
							default: return null;
						}
					} catch (java.lang.reflect.InvocationTargetException e) {
						Throwable cause = e.getCause();
						Log.error("Proxy invocation failed for " + method.getName(), cause);
						// Return safe defaults for boolean methods
						if (method.getReturnType() == boolean.class) return false;
						return null;
					}
				}
			);

			// Register with Lucee (requires secret)
			Method setListener = registryClass.getMethod("setListener", listenerInterface, String.class);
			Boolean success = (Boolean) setListener.invoke(null, listenerProxy, secret);

			if (success) {
				Log.info("Registered native debugger listener");
				return true;
			} else {
				Log.error("Debugger registration rejected - secret mismatch");
				return false;
			}
		} catch (ClassNotFoundException e) {
			Log.info("DebuggerRegistry not found - requires Lucee 7.1+");
			return false;
		} catch (NoSuchMethodException e) {
			Log.error("DebuggerRegistry.setListener(listener, secret) not found - requires updated Lucee 7.1+");
			return false;
		} catch (Throwable e) {
			Log.error("Failed to register listener", e);
			return false;
		}
	}

	// Note: finalize() was removed - it was being called by GC prematurely
	// and shutting down the DAP server. Now we keep a static reference to
	// prevent GC, and rely on DapServer.shutdown() being called explicitly
	// (via system properties) when the extension is reinstalled.
}
