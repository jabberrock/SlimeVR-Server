package io.eiren.util.logging;

import java.util.logging.Level;


public class Logger {

	private final String prefix;

	public Logger(String context) {
		prefix = "[" + context + "] ";
	}

	public void info(String message) {
		LogManager.info(makeMessage(message));
	}

	public void severe(String message) {
		LogManager.severe(makeMessage(message));
	}

	public void warning(String message) {
		LogManager.warning(makeMessage(message));
	}

	public void debug(String message) {
		LogManager.debug(makeMessage(message));
	}

	public void info(String message, Throwable t) {
		LogManager.info(makeMessage(message, t));
	}

	public void severe(String message, Throwable t) {
		LogManager.severe(makeMessage(message, t));
	}

	public void warning(String message, Throwable t) {
		LogManager.warning(makeMessage(message, t));
	}

	public void debug(String message, Throwable t) {
		LogManager.debug(makeMessage(message, t));
	}

	public void log(Level level, String message) {
		LogManager.log(level, makeMessage(message));
	}

	public void log(Level level, String message, Throwable t) {
		LogManager.log(level, makeMessage(message, t));
	}

	private String makeMessage(String message) {
		return prefix + message;
	}

	private String makeMessage(String message, Throwable t) {
		return prefix + message + ": " + t.getMessage();
	}
}
