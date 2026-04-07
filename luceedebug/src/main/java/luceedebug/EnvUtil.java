package luceedebug;

/**
 * Utility class for reading environment variables and system properties
 * in Lucee's naming convention.
 */
public final class EnvUtil {

	private EnvUtil() {}

	/**
	 * Get system property or environment variable.
	 * System property takes precedence. Env var name is derived from property name
	 * by uppercasing and replacing dots with underscores.
	 *
	 * @param propertyName e.g. "lucee.debugger.enabled"
	 * @return the value, or null if not set
	 */
	public static String getSystemPropOrEnvVar(String propertyName) {
		// Try system property first
		String value = System.getProperty(propertyName);
		if (value != null && !value.isEmpty()) {
			return value;
		}
		// Try env var (lucee.dap.port -> LUCEE_DAP_PORT)
		String envName = propertyName.toUpperCase().replace('.', '_');
		return System.getenv(envName);
	}

	/**
	 * Get DAP secret from environment/system property.
	 * Checks "lucee.dap.secret" / "LUCEE_DAP_SECRET".
	 *
	 * @return the secret, or null if not set (debugger disabled)
	 */
	public static String getDebuggerSecret() {
		String secret = getSystemPropOrEnvVar("lucee.dap.secret");
		if (secret != null && !secret.trim().isEmpty()) {
			return secret.trim();
		}
		return null;
	}

	/**
	 * Check if DAP breakpoint support is enabled.
	 * Reads ConfigImpl.DEBUGGER static field via reflection to match Lucee's state.
	 *
	 * @return true if DAP breakpoint support is enabled
	 */
	public static boolean isDebuggerEnabled() {
		try {
			Class<?> configImpl = Class.forName("lucee.runtime.config.ConfigImpl");
			java.lang.reflect.Field field = configImpl.getField("DEBUGGER");
			return (boolean) field.get(null);
		} catch (Exception e) {
			// Fallback to env var check if reflection fails (e.g. older Lucee)
			if (getDebuggerSecret() == null) {
				return false;
			}
			String bp = getSystemPropOrEnvVar("lucee.dap.breakpoint");
			return bp == null || "true".equalsIgnoreCase(bp.trim());
		}
	}

	/**
	 * Get DAP port from environment/system property.
	 * Checks "lucee.dap.port" / "LUCEE_DAP_PORT".
	 * Defaults to 9999 if secret is set but port is not.
	 *
	 * @return the port number, or -1 if debugger disabled (no secret)
	 */
	public static int getDebuggerPort() {
		// No port if no secret
		if (getDebuggerSecret() == null) {
			return -1;
		}
		String port = getSystemPropOrEnvVar("lucee.dap.port");
		if (port == null || port.isEmpty()) {
			return 9999; // default port
		}
		try {
			return Integer.parseInt(port);
		} catch (NumberFormatException e) {
			return 9999;
		}
	}
}
