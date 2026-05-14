package dev.slimevr.tracking.videocalibration.human

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.eiren.util.logging.Logger
import io.github.axisangles.ktmath.Vector2D
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.math.*

class RTMPoseEstimator(modelPath: Path) : HumanPoseEstimator {

	private val logger = Logger(this::class.simpleName)

	private val env = OrtEnvironment.getEnvironment()
	private val session: OrtSession

	private val inputImage = BufferedImage(POSE_INPUT_WIDTH, POSE_INPUT_HEIGHT, BufferedImage.TYPE_INT_RGB)
	private val inputRGB = IntArray(POSE_INPUT_WIDTH * POSE_INPUT_HEIGHT)
	private val inputNCHW = FloatArray(POSE_INPUT_WIDTH * POSE_INPUT_HEIGHT * 3)

	init {
		RTMPoseModel.loadDirectML()

		val options = OrtSession.SessionOptions()
		options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

		try {
			logger.info("Trying to enable DirectML inference...")
			options.addDirectML(0)
		} catch (e: Exception) {
			logger.warning("Failed to enable DirectML inference, using CPU for inference", e)
		}

		session = env.createSession(modelPath.absolutePathString(), options)
	}

	override fun estimate(image: BufferedImage): Map<HumanJoint, Vector2D> {
		if (image.type != BufferedImage.TYPE_INT_RGB) {
			error("image must be TYPE_INT_RGB")
		}

		updateInputImage(image)
		updateInputNCHW()

		val inputName = session.inputNames.first()
		val shape = longArrayOf(1, 3, inputImage.height.toLong(), inputImage.width.toLong())
		val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputNCHW), shape)

		session.run(mapOf(inputName to tensor)).use { output ->
			val pose = parsePoseOutput(output)
			return pose
				.mapIndexedNotNull { index, keypoint ->
					val joint = Halpe26Keypoint.indexToHumanJoint[index] ?: return@mapIndexedNotNull null
					if (keypoint.score < MIN_KEYPOINT_SCORE) {
						return@mapIndexedNotNull null
					}
					joint to keypointToImagePixel(image, keypoint)
				}
				.toMap()
		}
	}

	private fun updateInputImage(image: BufferedImage) {
		val g = inputImage.createGraphics()

		g.color = LETTERBOX_COLOR
		g.fillRect(0, 0, inputImage.width, inputImage.height)

		val scale = min(
			inputImage.width.toFloat() / image.width,
			inputImage.height.toFloat() / image.height,
		)

		val width = (image.width * scale).toInt()
		val height = (image.height * scale).toInt()
		val x = (inputImage.width - width) / 2
		val y = (inputImage.height - height) / 2

		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		g.drawImage(image, x, y, width, height, null)

		g.dispose()
	}

	private fun updateInputNCHW() {
		inputImage.getRGB(0, 0, inputImage.width, inputImage.height, inputRGB, 0, inputImage.width)

		val channelSize = inputImage.width * inputImage.height
		for (i in inputRGB.indices) {
			val p = inputRGB[i]
			val r = (p shr 16 and 0xFF) / 255.0f
			val g = (p shr 8 and 0xFF) / 255.0f
			val b = (p and 0xFF) / 255.0f
			inputNCHW[i] = r
			inputNCHW[channelSize + i] = g
			inputNCHW[2 * channelSize + i] = b
		}
	}

	private data class Keypoint(val x: Float, val y: Float, val score: Float)

	private fun parsePoseOutput(output: OrtSession.Result): List<Keypoint> {
		val simccXTensor = output[0] as? OnnxTensor ?: error("output[0] is not a tensor")
		val simccYTensor = output[1] as? OnnxTensor ?: error("output[1] is not a tensor")

		val xInfo = simccXTensor.info
		val yInfo = simccYTensor.info

		val xShape = xInfo.shape
		val yShape = yInfo.shape

		val kCount = xShape[1].toInt()
		val xBins = xShape[2].toInt()
		val yBins = yShape[2].toInt()
		val simccSplitRatioX = xBins.toFloat() / POSE_INPUT_WIDTH.toFloat()
		val simccSplitRatioY = yBins.toFloat() / POSE_INPUT_HEIGHT.toFloat()

		val xBuf = simccXTensor.floatBuffer.duplicate()
		val yBuf = simccYTensor.floatBuffer.duplicate()
		val xFlat = FloatArray(xBuf.remaining())
		val yFlat = FloatArray(yBuf.remaining())
		xBuf.get(xFlat)
		yBuf.get(yFlat)

		require(xFlat.size >= kCount * xBins && yFlat.size >= kCount * yBins) {
			"SimCC buffers are smaller than expected for one batch: " +
				"x=${xFlat.size} need>=${kCount * xBins}, y=${yFlat.size} need>=${kCount * yBins}"
		}

		val out = ArrayList<Keypoint>(kCount)
		for (i in 0 until kCount) {
			val (xIdx, xScore) = argMaxSlice(xFlat, i * xBins, xBins)
			val (yIdx, yScore) = argMaxSlice(yFlat, i * yBins, yBins)
			val x = xIdx.toFloat() / simccSplitRatioX
			val y = yIdx.toFloat() / simccSplitRatioY
			out.add(Keypoint(x = x, y = y, score = min(xScore, yScore)))
		}

		return out
	}

	private fun argMaxSlice(arr: FloatArray, offset: Int, length: Int): Pair<Int, Float> {
		if (length <= 0) return 0 to 0f
		var idx = 0
		var best = arr[offset]
		var i = 1
		while (i < length) {
			val v = arr[offset + i]
			if (v > best) {
				best = v
				idx = i
			}
			i += 1
		}
		return idx to best
	}

	private fun keypointToImagePixel(image: BufferedImage, k: Keypoint): Vector2D {
		val scale = min(
			inputImage.width.toFloat() / image.width,
			inputImage.height.toFloat() / image.height,
		)

		val width = (image.width * scale).toInt()
		val height = (image.height * scale).toInt()
		val x = (inputImage.width - width) / 2
		val y = (inputImage.height - height) / 2

		return Vector2D((k.x.toDouble() - x) / scale, (k.y.toDouble() - y) / scale)
	}

	companion object {
		private const val POSE_INPUT_WIDTH = 192
		private const val POSE_INPUT_HEIGHT = 256
		private val LETTERBOX_COLOR = Color(114, 114, 114)
		private const val MIN_KEYPOINT_SCORE = 0.6f
	}
}
