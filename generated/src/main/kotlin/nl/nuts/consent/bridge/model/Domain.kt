package nl.nuts.consent.bridge.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonValue
import javax.validation.Valid
import javax.validation.constraints.*

/**
* 
* Values: mEDICAL,pGO,iNSURANCE,sOCIAL
*/
enum class Domain(val value: String) {

    mEDICAL("MEDICAL"),

    pGO("PGO"),

    iNSURANCE("INSURANCE"),

    sOCIAL("SOCIAL");

}

