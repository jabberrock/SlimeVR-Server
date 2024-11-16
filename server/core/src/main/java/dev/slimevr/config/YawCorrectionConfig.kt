package dev.slimevr.config

class YawCorrectionConfig {
	private var _amountInDegPerSec = 0.0f

	var amountInDegPerSec: Float
		get() = _amountInDegPerSec
		set(value) { _amountInDegPerSec = if (value > 0.0f) value else 0.0f }
}
