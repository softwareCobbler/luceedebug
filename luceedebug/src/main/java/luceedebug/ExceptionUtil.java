package luceedebug;

/**
 * Utility class for extracting information from Lucee exceptions.
 * Uses reflection to handle OSGi classloader isolation.
 */
public final class ExceptionUtil {

	private ExceptionUtil() {}

	/**
	 * Get the first CFML location from an exception's stack trace.
	 * @return "template:line" or null if no CFML frame found
	 */
	public static String getFirstCfmlLocation(Throwable ex) {
		// First try tagContext (more accurate for Lucee PageExceptions)
		String tagContextLocation = getFirstTagContextLocation(ex);
		if (tagContextLocation != null) {
			return tagContextLocation;
		}
		// Fallback to Java stack trace
		for (StackTraceElement ste : ex.getStackTrace()) {
			if (ste.getClassName().endsWith("$cf")) {
				return ste.getFileName() + ":" + ste.getLineNumber();
			}
		}
		return null;
	}

	/**
	 * Get the full CFML stack trace from a PageException's tagContext.
	 * @return Multi-line string of "template:line" entries, or null if not available
	 */
	public static String getCfmlStackTrace(Throwable ex) {
		try {
			// Get Config via reflection (OSGi classloader isolation)
			ClassLoader loader = ex.getClass().getClassLoader();
			Class<?> tlpcClass = loader.loadClass("lucee.runtime.engine.ThreadLocalPageContext");
			java.lang.reflect.Method getConfig = tlpcClass.getMethod("getConfig");
			Object config = getConfig.invoke(null);
			if (config == null) {
				return null;
			}
			// Check if it's a PageException with getTagContext(Config)
			Class<?> configClass = loader.loadClass("lucee.runtime.config.Config");
			java.lang.reflect.Method getTagContext = ex.getClass().getMethod("getTagContext", configClass);
			Object tagContext = getTagContext.invoke(ex, config);
			if (tagContext == null) {
				return null;
			}
			// tagContext is a lucee.runtime.type.Array
			StringBuilder sb = new StringBuilder();
			java.lang.reflect.Method size = tagContext.getClass().getMethod("size");
			java.lang.reflect.Method getE = tagContext.getClass().getMethod("getE", int.class);
			int len = (Integer) size.invoke(tagContext);

			// Get KeyImpl.init for creating keys
			Class<?> keyImplClass = loader.loadClass("lucee.runtime.type.KeyImpl");
			java.lang.reflect.Method keyInit = keyImplClass.getMethod("init", String.class);
			Object templateKey = keyInit.invoke(null, "template");
			Object lineKey = keyInit.invoke(null, "line");

			// Get the Struct.get(Key, defaultValue) method
			Class<?> keyClass = loader.loadClass("lucee.runtime.type.Collection$Key");

			for (int i = 1; i <= len; i++) {
				Object item = getE.invoke(tagContext, i);
				// item is a Struct with template, line, codePrintPlain
				java.lang.reflect.Method get = item.getClass().getMethod("get", keyClass, Object.class);
				String template = (String) get.invoke(item, templateKey, "");
				Object lineObj = get.invoke(item, lineKey, 0);
				int line = lineObj instanceof Number ? ((Number) lineObj).intValue() : 0;
				sb.append(template).append(":").append(line).append("\n");
			}
			return sb.toString();
		} catch (Exception e) {
			Log.debug("getCfmlStackTrace failed: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get the first location from tagContext.
	 */
	private static String getFirstTagContextLocation(Throwable ex) {
		try {
			ClassLoader loader = ex.getClass().getClassLoader();
			Class<?> tlpcClass = loader.loadClass("lucee.runtime.engine.ThreadLocalPageContext");
			java.lang.reflect.Method getConfig = tlpcClass.getMethod("getConfig");
			Object config = getConfig.invoke(null);
			if (config == null) {
				return null;
			}
			Class<?> configClass = loader.loadClass("lucee.runtime.config.Config");
			java.lang.reflect.Method getTagContext = ex.getClass().getMethod("getTagContext", configClass);
			Object tagContext = getTagContext.invoke(ex, config);
			if (tagContext == null) {
				return null;
			}
			java.lang.reflect.Method size = tagContext.getClass().getMethod("size");
			int len = (Integer) size.invoke(tagContext);
			if (len == 0) {
				return null;
			}
			java.lang.reflect.Method getE = tagContext.getClass().getMethod("getE", int.class);
			Object item = getE.invoke(tagContext, 1);

			// Create keys for struct access
			Class<?> keyImplClass = loader.loadClass("lucee.runtime.type.KeyImpl");
			java.lang.reflect.Method keyInit = keyImplClass.getMethod("init", String.class);
			Object templateKey = keyInit.invoke(null, "template");
			Object lineKey = keyInit.invoke(null, "line");
			Class<?> keyClass = loader.loadClass("lucee.runtime.type.Collection$Key");

			java.lang.reflect.Method get = item.getClass().getMethod("get", keyClass, Object.class);
			String template = (String) get.invoke(item, templateKey, "");
			Object lineObj = get.invoke(item, lineKey, 0);
			int line = lineObj instanceof Number ? ((Number) lineObj).intValue() : 0;
			return template + ":" + line;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Get the CFML stack trace, falling back to filtered Java stack trace.
	 */
	public static String getCfmlStackTraceOrFallback(Throwable ex) {
		String stackTrace = getCfmlStackTrace(ex);
		if (stackTrace != null && !stackTrace.isEmpty()) {
			return stackTrace;
		}
		// Fallback to Java stack trace filtered to CFML frames
		StringBuilder sb = new StringBuilder();
		for (StackTraceElement ste : ex.getStackTrace()) {
			if (ste.getClassName().endsWith("$cf")) {
				sb.append(ste.getFileName()).append(":").append(ste.getLineNumber()).append("\n");
			}
		}
		return sb.toString();
	}

	/**
	 * Get exception detail from PageException.getDetail() via reflection.
	 */
	public static String getDetail(Throwable ex) {
		try {
			java.lang.reflect.Method getDetail = ex.getClass().getMethod("getDetail");
			return (String) getDetail.invoke(ex);
		} catch (Exception e) {
			return null;
		}
	}
}
