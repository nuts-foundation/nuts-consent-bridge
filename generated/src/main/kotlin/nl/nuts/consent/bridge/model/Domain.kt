package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonValue
import javax.validation.Valid
import javax.validation.constraints.*

/**
* 
* Values: medical,pgo,insurance
*/
enum class Domain(val value: String) {

    medical("medical"),

    pgo("pgo"),

    insurance("insurance");

}

