package luceedebug;

import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.OutputEventArgumentsCategory;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

import lucee.loader.engine.CFMLEngineFactory;

/**
 * Centralized logging for luceedebug.
 * Routes all log messages through a common method that:
 * - Writes to System.out with [luceedebug] prefix
 * - Optionally sends to DAP OutputEvent when a client is connected
 * - Supports ANSI colors (configurable via launch.json colorLogs, default true)
 * - Respects log level (configurable via launch.json logLevel, default info)
 * - Writes errors to Lucee's exception.log when available
 */
public class Log {
	private static final String PREFIX = "[luceedebug] ";
	private static final String APP_NAME = "luceedebug";

	// ANSI escape codes (for console/tomcat output)
	private static final String ANSI_RESET = "\u001b[0m";
	private static final String ANSI_RED = "\u001b[31m";
	private static final String ANSI_YELLOW = "\u001b[33m";
	private static final String ANSI_CYAN = "\u001b[36m";
	private static final String ANSI_DIM = "\u001b[2m";

	// DAP client for sending OutputEvents (set when client connects)
	private static volatile IDebugProtocolClient dapClient = null;

	// Runtime settings from launch.json
	private static volatile boolean colorLogs = true;
	private static volatile LogLevel logLevel = LogLevel.INFO;
	private static volatile boolean logExceptions = false;
	private static volatile boolean consoleOutput = false;

	// Internal debugging - only enabled via env var LUCEE_DEBUGGER_DEBUG
	private static final boolean internalDebug;
	static {
		String env = System.getenv("LUCEE_DEBUGGER_DEBUG");
		internalDebug = env != null && !env.isEmpty() && !env.equals("0") && !env.equalsIgnoreCase("false");
	}

	public enum LogLevel {
		ERROR(0),
		INFO(1),
		DEBUG(2),
		TRACE(3);

		private final int level;

		LogLevel(int level) {
			this.level = level;
		}

		public boolean isEnabled(LogLevel threshold) {
			return this.level <= threshold.level;
		}
	}

	/**
	 * Set the DAP client to receive log messages as OutputEvents.
	 * Pass null to disable DAP output (e.g., on disconnect).
	 */
	public static void setDapClient(IDebugProtocolClient client) {
		dapClient = client;
	}

	/**
	 * Set color logs setting from launch.json.
	 */
	public static void setColorLogs(boolean enabled) {
		colorLogs = enabled;
	}

	/**
	 * Set log level from launch.json.
	 */
	public static void setLogLevel(LogLevel level) {
		logLevel = level;
	}

	/**
	 * Set exception logging from launch.json.
	 */
	public static void setLogExceptions(boolean enabled) {
		logExceptions = enabled;
	}

	/**
	 * Set console output streaming from launch.json.
	 * When enabled, we skip sending directly to DAP since System.out/err
	 * will be captured and forwarded via systemOutput().
	 */
	public static void setConsoleOutput(boolean enabled) {
		consoleOutput = enabled;
	}

	/**
	 * Log an info message to console and optionally to DAP client.
	 * Only logged if log level is INFO or higher.
	 */
	public static void info(String message) {
		if (!LogLevel.INFO.isEnabled(logLevel)) {
			return;
		}
		// When consoleOutput is enabled, skip System.out (it gets captured and
		// forwarded to DAP, causing double-logging). Send directly to DAP instead.
		if (!consoleOutput) {
			String consoleMsg;
			if (colorLogs) {
				consoleMsg = ANSI_CYAN + PREFIX + ANSI_RESET + message;
			} else {
				consoleMsg = PREFIX + message;
			}
			System.out.println(consoleMsg);
		}
		sendToDap(message, OutputEventArgumentsCategory.CONSOLE);
	}

	/**
	 * Log an error message. Always logged regardless of log level.
	 * Also logs to Lucee's exception.log when available.
	 */
	public static void error(String message) {
		if (!consoleOutput) {
			String consoleMsg;
			if (colorLogs) {
				consoleMsg = ANSI_RED + PREFIX + "ERROR: " + message + ANSI_RESET;
			} else {
				consoleMsg = PREFIX + "ERROR: " + message;
			}
			System.out.println(consoleMsg);
		}
		sendToDap("ERROR: " + message, OutputEventArgumentsCategory.STDERR);
		logToLuceeException(message);
	}

	/**
	 * Log an error with exception.
	 * Also logs to Lucee's exception.log when available.
	 */
	public static void error(String message, Throwable t) {
		error(message + ": " + t.getMessage());
		t.printStackTrace();
		logToLuceeException(message, t);
	}

	/**
	 * Log a debug message.
	 * Only printed if LUCEE_DEBUGGER_DEBUG env var is set.
	 * Uses STDOUT category in DAP for normal (non-highlighted) display.
	 */
	public static void debug(String message) {
		if (!internalDebug) {
			return;
		}
		if (!consoleOutput) {
			String consoleMsg;
			if (colorLogs) {
				consoleMsg = ANSI_DIM + PREFIX + "DEBUG: " + message + ANSI_RESET;
			} else {
				consoleMsg = PREFIX + "DEBUG: " + message;
			}
			System.out.println(consoleMsg);
		}
		sendToDap("DEBUG: " + message, OutputEventArgumentsCategory.STDOUT);
	}

	/**
	 * Log a trace message.
	 * Only printed if LUCEE_DEBUGGER_DEBUG env var is set.
	 * Uses STDOUT category in DAP for normal (non-highlighted) display.
	 */
	public static void trace(String message) {
		if (!internalDebug) {
			return;
		}
		if (!consoleOutput) {
			String consoleMsg;
			if (colorLogs) {
				consoleMsg = ANSI_DIM + PREFIX + "TRACE: " + message + ANSI_RESET;
			} else {
				consoleMsg = PREFIX + "TRACE: " + message;
			}
			System.out.println(consoleMsg);
		}
		sendToDap("TRACE: " + message, OutputEventArgumentsCategory.STDOUT);
	}

	/**
	 * Log a warning message.
	 * Only logged if log level is INFO or higher.
	 * Uses IMPORTANT category in DAP for highlighted display.
	 */
	public static void warn(String message) {
		if (!LogLevel.INFO.isEnabled(logLevel)) {
			return;
		}
		if (!consoleOutput) {
			String consoleMsg;
			if (colorLogs) {
				consoleMsg = ANSI_YELLOW + PREFIX + "WARN: " + message + ANSI_RESET;
			} else {
				consoleMsg = PREFIX + "WARN: " + message;
			}
			System.out.println(consoleMsg);
		}
		sendToDap("WARN: " + message, OutputEventArgumentsCategory.IMPORTANT);
	}

	/**
	 * Log an exception to the debug console (if logExceptions is enabled).
	 * Only sends to DAP, not to System.out.
	 */
	public static void exception(Throwable t) {
		if (!logExceptions) {
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());

		// Get full CFML stack trace
		String stackTrace = ExceptionUtil.getCfmlStackTraceOrFallback(t);
		if (stackTrace != null && !stackTrace.isEmpty()) {
			for (String line : stackTrace.split("\n")) {
				if (!line.isEmpty()) {
					sb.append("\n  at ").append(line);
				}
			}
		} else {
			sb.append("\n  at unknown");
		}
		sendToDap(sb.toString(), OutputEventArgumentsCategory.STDERR);
	}

	/**
	 * Forward System.out/err output to DAP client.
	 * Called by NativeDebuggerListener.onOutput() when consoleOutput is enabled.
	 * Does NOT echo to console (would cause infinite loop).
	 *
	 * @param text The text that was written
	 * @param isStdErr true if stderr, false if stdout
	 */
	public static void systemOutput(String text, boolean isStdErr) {
		IDebugProtocolClient client = dapClient;
		if (client != null) {
			try {
				var args = new OutputEventArguments();
				args.setCategory(isStdErr ? OutputEventArgumentsCategory.STDERR : OutputEventArgumentsCategory.STDOUT);
				// Pass through as-is - the source already includes newlines
				args.setOutput(text);
				client.output(args);
			} catch (Exception e) {
				// Silently ignore - can't log here or we'd recurse
			}
		}
	}

	/**
	 * Send log to DAP client if connected.
	 */
	private static void sendToDap(String message, String category) {
		IDebugProtocolClient client = dapClient;
		if (client != null) {
			try {
				var args = new OutputEventArguments();
				args.setCategory(category);
				args.setOutput("[luceedebug] " + message + "\n");
				client.output(args);
			} catch (Exception e) {
				// Don't recursively log - just print to console
				System.out.println(PREFIX + "Failed to send to DAP: " + e.getMessage());
			}
		}
	}

	/**
	 * Get Lucee's exception log if available.
	 * Returns null if Lucee is not running or log cannot be obtained.
	 */
	private static lucee.commons.io.log.Log getLuceeExceptionLog() {
		try {
			var config = CFMLEngineFactory.getInstance().getThreadConfig();
			if (config != null) {
				return config.getLog("exception");
			}
		} catch (Throwable t) {
			// Lucee not available or not initialized yet - silently ignore
		}
		return null;
	}

	/**
	 * Log an error to Lucee's exception.log if available.
	 */
	private static void logToLuceeException(String message) {
		var luceeLog = getLuceeExceptionLog();
		if (luceeLog != null) {
			luceeLog.error(APP_NAME, message);
		}
	}

	/**
	 * Log an error with throwable to Lucee's exception.log if available.
	 */
	private static void logToLuceeException(String message, Throwable t) {
		var luceeLog = getLuceeExceptionLog();
		if (luceeLog != null) {
			luceeLog.error(APP_NAME, message, t);
		}
	}
}
