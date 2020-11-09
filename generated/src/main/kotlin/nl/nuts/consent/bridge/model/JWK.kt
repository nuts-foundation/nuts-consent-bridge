package nl.nuts.consent.bridge.model

import java.util.Objects
import javax.validation.Valid
import javax.validation.constraints.*

/**
 * as described by https://tools.ietf.org/html/rfc7517. Modelled as object so libraries can parse the tokens themselves.
 *
 * This file is "handcrafted" as the OpenAPITools Code Generator doesn't properly generate empty OpenAPI types with
 * 'object' as base type.
 */
class JWK(source: Map<String, Any>) : HashMap<String, Any>(source) {

    constructor() : this(HashMap())
}