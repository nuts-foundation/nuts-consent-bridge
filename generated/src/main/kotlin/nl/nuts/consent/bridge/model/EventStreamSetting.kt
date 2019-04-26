package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * 
 * @param topic 
 * @param epoch 
 */
data class EventStreamSetting (

        @get:NotNull 
        @JsonProperty("topic") val topic: String,

        @get:NotNull 
        @JsonProperty("epoch") val epoch: Long
) {

}

