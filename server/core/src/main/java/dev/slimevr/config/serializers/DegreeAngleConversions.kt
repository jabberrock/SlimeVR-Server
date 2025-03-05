package dev.slimevr.config.serializers

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import dev.slimevr.math.DegreeAngle

object DegreeAngleConversions {

	object Serializer : JsonSerializer<DegreeAngle>() {
		override fun serialize(
			value: DegreeAngle,
			generator: JsonGenerator,
			serializer: SerializerProvider,
		) {
			generator.writeNumber(value.toDeg())
		}
	}

	object Deserializer : JsonDeserializer<DegreeAngle>() {
		override fun deserialize(parser: JsonParser, context: DeserializationContext): DegreeAngle =
			DegreeAngle(parser.numberValue.toFloat())
	}
}
